/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.line;

import io.questdb.cairo.*;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.*;
import io.questdb.std.microtime.MicrosecondClock;
import io.questdb.std.str.Path;

import java.io.Closeable;

import static io.questdb.cairo.TableUtils.TABLE_DOES_NOT_EXIST;
import static io.questdb.cairo.TableUtils.TABLE_EXISTS;

public class CairoLineProtoParser implements LineProtoParser, Closeable {
    private final static Log LOG = LogFactory.getLog(CairoLineProtoParser.class);
    private static final LineEndParser NOOP_LINE_END = cache -> {
    };
    private static final FieldValueParser NOOP_FIELD_VALUE = (value, cache) -> {
    };
    private static final FieldNameParser NOOP_FIELD_NAME = name -> {
    };
    private static ObjList<ColumnWriter> writers = new ObjList<>();

    static {
        writers.extendAndSet(ColumnType.LONG, CairoLineProtoParser::putLong);
        writers.extendAndSet(ColumnType.BOOLEAN, CairoLineProtoParser::putBoolean);
        writers.extendAndSet(ColumnType.STRING, CairoLineProtoParser::putStr);
        writers.extendAndSet(ColumnType.SYMBOL, CairoLineProtoParser::putSymbol);
        writers.extendAndSet(ColumnType.DOUBLE, CairoLineProtoParser::putDouble);
    }

    private final CairoEngine engine;
    private final CharSequenceObjHashMap<CacheEntry> writerCache = new CharSequenceObjHashMap<>();
    private final CharSequenceObjHashMap<TableWriter> commitList = new CharSequenceObjHashMap<>();
    private final Path path = new Path();
    private final CairoConfiguration configuration;
    private final LongList columnNameType = new LongList();
    private final LongList columnIndexAndType = new LongList();
    private final LongList columnValues = new LongList();
    private final AppendMemory appendMemory = new AppendMemory();
    private final MicrosecondClock clock;
    private final FieldNameParser MY_NEW_FIELD_NAME = this::parseFieldNameNewTable;
    private final FieldValueParser MY_NEW_TAG_VALUE = this::parseTagValueNewTable;
    private final TableStructureAdapter tableStructureAdapter = new TableStructureAdapter();
    private final CairoSecurityContext cairoSecurityContext;
    private final LineProtoTimestampAdapter timestampAdapter;
    // state
    // cache entry index is always a negative value
    private int cacheEntryIndex = 0;
    private TableWriter writer;
    private final LineEndParser MY_LINE_END = this::appendRow;
    private RecordMetadata metadata;
    private int columnCount;
    private int columnIndex;
    private long columnName;
    private int columnType;
    private final FieldNameParser MY_FIELD_NAME = this::parseFieldName;
    private long tableName;
    private final LineEndParser MY_NEW_LINE_END = this::createTableAndAppendRow;
    private LineEndParser onLineEnd;
    private FieldNameParser onFieldName;
    private FieldValueParser onFieldValue;
    private FieldValueParser onTagValue;
    private final FieldValueParser MY_FIELD_VALUE = this::parseFieldValue;
    private final FieldValueParser MY_NEW_FIELD_VALUE = this::parseFieldValueNewTable;
    private final FieldValueParser MY_TAG_VALUE = this::parseTagValue;

    public CairoLineProtoParser(
            CairoEngine engine,
            CairoSecurityContext cairoSecurityContext,
            LineProtoTimestampAdapter timestampAdapter
    ) {
        this.configuration = engine.getConfiguration();
        this.clock = configuration.getMicrosecondClock();
        this.engine = engine;
        this.cairoSecurityContext = cairoSecurityContext;
        this.timestampAdapter = timestampAdapter;
    }

    private static boolean isTrue(CharSequence value) {
        final char firstChar = value.charAt(0);
        return firstChar == 't' || firstChar == 'T';
    }

    private static void putSymbol(TableWriter.Row row, int index, CharSequence value) {
        row.putSym(index, value);
    }

    private static void putStr(TableWriter.Row row, int index, CharSequence value) {
        row.putStr(index, value, 1, value.length() - 2);
    }

    private static void putBoolean(TableWriter.Row row, int index, CharSequence value) {
        row.putBool(index, isTrue(value));
    }

    private static void putDouble(TableWriter.Row row, int index, CharSequence value) throws BadCastException {
        try {
            row.putDouble(index, Numbers.parseDouble(value));
        } catch (NumericException e) {
            LOG.error().$("not a DOUBLE: ").$(value).$();
            throw BadCastException.INSTANCE;
        }
    }

    private static void putLong(TableWriter.Row row, int index, CharSequence value) throws BadCastException {
        try {
            row.putLong(index, Numbers.parseLong(value, 0, value.length() - 1));
        } catch (NumericException e) {
            LOG.error().$("not an INT: ").$(value).$();
            throw BadCastException.INSTANCE;
        }
    }

    @Override
    public void close() {
        Misc.free(path);
        Misc.free(appendMemory);
        for (int i = 0, n = writerCache.size(); i < n; i++) {
            Misc.free(writerCache.valueQuick(i).writer);
        }
    }

    public void commitAll() {
        if (writer != null) {
            writer.commit();
        }
        for (int i = 0, n = commitList.size(); i < n; i++) {
            commitList.valueQuick(i).commit();
        }
        commitList.clear();
    }

    @Override
    public void onError(int position, int state, int code) {
        clearState();
    }

    @Override
    public void onEvent(CachedCharSequence token, int eventType, CharSequenceCache cache) {

        switch (eventType) {
            case EVT_MEASUREMENT:
                int wrtIndex = writerCache.keyIndex(token);
                // this condition relies on the fact that this.cacheEntryIndex is always negative
                // which indicates that entry is in cache
                if (wrtIndex == this.cacheEntryIndex) {
                    // same table as from last line?
                    // make sure we append it in case it was in "create" mode
                    if (writer != null) {
                        switchModeToAppend();
                    } else {
                        initCacheEntry(token, writerCache.valueAt(wrtIndex));
                    }
                } else {
                    switchTable(token, wrtIndex);
                }
                break;
            case EVT_FIELD_NAME:
            case EVT_TAG_NAME:
                onFieldName.parse(token);
                break;
            case EVT_TAG_VALUE:
                onTagValue.parse(token, cache);
                break;
            case EVT_FIELD_VALUE:
                onFieldValue.parse(token, cache);
                break;
            case EVT_TIMESTAMP:
                columnValues.add(token.getCacheAddress());
                break;
            default:
                break;
        }
    }

    @Override
    public void onLineEnd(CharSequenceCache cache) {
        try {
            onLineEnd.parse(cache);
        } catch (CairoException e) {
            LOG.error().$((Sinkable) e).$();
        }
        clearState();
    }

    private void appendFirstRowAndCacheWriter(CharSequenceCache cache) {
        TableWriter writer = engine.getWriter(cairoSecurityContext, cache.get(tableName));
        this.writer = writer;
        this.metadata = writer.getMetadata();
        this.columnCount = metadata.getColumnCount();
        writerCache.valueAt(cacheEntryIndex).writer = writer;

        final int columnCount = columnNameType.size() / 2;
        final TableWriter.Row row = createNewRow(cache, columnCount);
        if (row == null) {
            return;
        }

        try {
            for (int i = 0; i < columnCount; i++) {
                putValue(row
                        , i
                        , (int) columnNameType.getQuick(i * 2 + 1)
                        , cache.get(columnValues.getQuick(i)));
            }
            row.append();
        } catch (BadCastException ignore) {
            row.cancel();
        }
    }

    private void appendRow(CharSequenceCache cache) {
        final int columnCount = columnIndexAndType.size();
        final TableWriter.Row row = createNewRow(cache, columnCount);
        if (row == null) {
            return;
        }

        try {
            for (int i = 0; i < columnCount; i++) {
                final long value = columnIndexAndType.getQuick(i);
                putValue(row
                        , Numbers.decodeLowInt(value)
                        , Numbers.decodeHighInt(value)
                        , cache.get(columnValues.getQuick(i))
                );
            }
            row.append();
        } catch (BadCastException ignore) {
            row.cancel();
        }
    }

    private void clearState() {
        columnNameType.clear();
        columnIndexAndType.clear();
        columnValues.clear();
    }

    private void cacheWriter(CacheEntry entry, CachedCharSequence tableName) {
        try {
            entry.writer = engine.getWriter(cairoSecurityContext, tableName);
            this.tableName = tableName.getCacheAddress();
            createState(entry);
            LOG.info().$("cached writer [name=").$(tableName).$(']').$();
        } catch (CairoException ex) {
            LOG.error().$((Sinkable) ex).$();
            switchModeToSkipLine();
        }
    }

    private TableWriter.Row createNewRow(CharSequenceCache cache, int columnCount) {
        final int valueCount = columnValues.size();
        if (columnCount == valueCount) {
            return writer.newRow(clock.getTicks());
        } else {
            try {
                return writer.newRow(timestampAdapter.getMicros(cache.get(columnValues.getQuick(valueCount - 1))));
            } catch (NumericException e) {
                LOG.error().$("invalid timestamp: ").$(cache.get(columnValues.getQuick(valueCount - 1))).$();
                return null;
            }
        }
    }

    private void createState(CacheEntry entry) {
        writer = entry.writer;
        metadata = writer.getMetadata();
        columnCount = metadata.getColumnCount();
        switchModeToAppend();
    }

    private void createTableAndAppendRow(CharSequenceCache cache) {
        engine.creatTable(
                cairoSecurityContext,
                appendMemory,
                path,
                tableStructureAdapter.of(cache)
        );
        appendFirstRowAndCacheWriter(cache);
    }

    private int getValueType(CharSequence token) {
        int len = token.length();
        switch (token.charAt(len - 1)) {
            case 'i':
                return ColumnType.LONG;
            case 'e':
                // tru(e)
                // fals(e)
            case 't':
            case 'T':
                // t
                // T
            case 'f':
            case 'F':
                // f
                // F
                return ColumnType.BOOLEAN;
            case '"':
                if (len < 2 || token.charAt(0) != '\"') {
                    LOG.error().$("incorrectly quoted string: ").$(token).$();
                    return -1;
                }
                return ColumnType.STRING;
            default:
                return ColumnType.DOUBLE;
        }
    }

    private void initCacheEntry(CachedCharSequence token, CacheEntry entry) {
        switch (entry.state) {
            case 0:
                int exists = engine.getStatus(cairoSecurityContext, path, token);
                switch (exists) {
                    case TABLE_EXISTS:
                        entry.state = 1;
                        cacheWriter(entry, token);
                        break;
                    case TABLE_DOES_NOT_EXIST:
                        tableName = token.getCacheAddress();
                        if (onLineEnd != MY_NEW_LINE_END) {
                            onLineEnd = MY_NEW_LINE_END;
                            onFieldName = MY_NEW_FIELD_NAME;
                            onFieldValue = MY_NEW_FIELD_VALUE;
                            onTagValue = MY_NEW_TAG_VALUE;
                        }
                        break;
                    default:
                        entry.state = 3;
                        switchModeToSkipLine();
                        break;
                }
                break;
            case 1:
                cacheWriter(entry, token);
                break;
            default:
                switchModeToSkipLine();
                break;
        }
    }

    private void parseFieldName(CachedCharSequence token) {
        columnIndex = metadata.getColumnIndexQuiet(token);
        if (columnIndex > -1) {
            columnType = metadata.getColumnType(columnIndex);
        } else {
            prepareNewColumn(token);
        }
    }

    private void parseValue(CachedCharSequence value, int valueType, CharSequenceCache cache) {
        if (columnType == valueType) {
            columnIndexAndType.add(Numbers.encodeLowHighInts(columnIndex, valueType));
            columnValues.add(value.getCacheAddress());
        } else {
            possibleNewColumn(value, valueType, cache);
        }
    }

    private void parseFieldNameNewTable(CachedCharSequence token) {
        columnNameType.add(token.getCacheAddress());
    }

    private void parseFieldValue(CachedCharSequence value, CharSequenceCache cache) {
        int valueType = getValueType(value);
        if (valueType == -1) {
            switchModeToSkipLine();
        } else {
            parseValue(value, valueType, cache);
        }
    }

    @SuppressWarnings("unused")
    private void parseFieldValueNewTable(CachedCharSequence value, CharSequenceCache cache) {
        int valueType = getValueType(value);
        if (valueType == -1) {
            switchModeToSkipLine();
        } else {
            parseValueNewTable(value, valueType);
        }
    }

    private void parseTagValue(CachedCharSequence value, CharSequenceCache cache) {
        parseValue(value, ColumnType.SYMBOL, cache);
    }

    @SuppressWarnings("unused")
    private void parseTagValueNewTable(CachedCharSequence value, CharSequenceCache cache) {
        parseValueNewTable(value, ColumnType.SYMBOL);
    }

    private void possibleNewColumn(CachedCharSequence value, int valueType, CharSequenceCache cache) {
        if (columnIndex > -1) {
            LOG.error().$("mismatched column and value types [table=").$(writer.getName())
                    .$(", column=").$(metadata.getColumnName(columnIndex))
                    .$(", columnType=").$(ColumnType.nameOf(columnType))
                    .$(", valueType=").$(ColumnType.nameOf(valueType))
                    .$(']').$();
            switchModeToSkipLine();
        } else {
            columnIndexAndType.add(Numbers.encodeLowHighInts(columnCount++, valueType));
            writer.addColumn(cache.get(columnName), valueType);
            columnValues.add(value.getCacheAddress());
        }
    }

    private void prepareNewColumn(CachedCharSequence token) {
        columnName = token.getCacheAddress();
        columnType = -1;
    }

    private void parseValueNewTable(CachedCharSequence value, int valueType) {
        columnNameType.add(valueType);
        columnValues.add(value.getCacheAddress());
    }

    /**
     * Writes column value to table row. CharSequence value is interpreted depending on
     * column type and written to column, identified by columnIndex. If value cannot be
     * cast to column type, #BadCastException is thrown.
     *
     * @param row        table row
     * @param index      index of column to write value to
     * @param columnType column type value will be cast to
     * @param value      value characters
     */
    private void putValue(TableWriter.Row row, int index, int columnType, CharSequence value) throws BadCastException {
        writers.getQuick(columnType).write(row, index, value);
    }

    private void switchModeToAppend() {
        if (onLineEnd != MY_LINE_END) {
            onLineEnd = MY_LINE_END;
            onFieldName = MY_FIELD_NAME;
            onFieldValue = MY_FIELD_VALUE;
            onTagValue = MY_TAG_VALUE;
        }
    }

    private void switchModeToSkipLine() {
        if (onFieldValue != NOOP_FIELD_VALUE) {
            onFieldValue = NOOP_FIELD_VALUE;
            onFieldName = NOOP_FIELD_NAME;
            onTagValue = NOOP_FIELD_VALUE;
            onLineEnd = NOOP_LINE_END;
        }
    }

    private void switchTable(CachedCharSequence tableName, int entryIndex) {
        if (this.cacheEntryIndex != 0) {
            // add previous writer to commit list
            CacheEntry e = writerCache.valueAt(cacheEntryIndex);
            if (e.writer != null) {
                commitList.put(e.writer.getName(), e.writer);
            }
        }

        CacheEntry entry;
        if (entryIndex < 0) {
            entry = writerCache.valueAt(entryIndex);
        } else {
            entry = new CacheEntry();
            writerCache.putAt(entryIndex, Chars.stringOf(tableName), entry);
            // adjust writer map index to negative, which indicates that entry exists
            entryIndex = -entryIndex - 1;
        }

        this.cacheEntryIndex = entryIndex;

        if (entry.writer == null) {
            initCacheEntry(tableName, entry);
        } else {
            createState(entry);
        }
    }

    private interface ColumnWriter {
        void write(TableWriter.Row row, int columnIndex, CharSequence value) throws BadCastException;
    }

    @FunctionalInterface
    private interface LineEndParser {
        void parse(CharSequenceCache cache);
    }

    @FunctionalInterface
    private interface FieldNameParser {
        void parse(CachedCharSequence name);
    }

    @FunctionalInterface
    private interface FieldValueParser {
        void parse(CachedCharSequence value, CharSequenceCache cache);
    }

    private static class BadCastException extends Exception {
        private static final BadCastException INSTANCE = new BadCastException();
    }

    private static class CacheEntry {
        private TableWriter writer;
        private int state = 0;
    }

    private class TableStructureAdapter implements TableStructure {
        private CharSequenceCache cache;
        private int columnCount;
        private int timestampIndex;

        @Override
        public int getColumnCount() {
            return columnCount;
        }

        @Override
        public CharSequence getColumnName(int columnIndex) {
            if (columnIndex == getTimestampIndex()) {
                return "timestamp";
            }
            return cache.get(columnNameType.getQuick(columnIndex * 2));
        }

        @Override
        public int getColumnType(int columnIndex) {
            if (columnIndex == getTimestampIndex()) {
                return ColumnType.TIMESTAMP;
            }
            return (int) columnNameType.getQuick(columnIndex * 2 + 1);
        }

        @Override
        public int getIndexBlockCapacity(int columnIndex) {
            return 0;
        }

        @Override
        public boolean getIndexedFlag(int columnIndex) {
            return false;
        }

        @Override
        public int getPartitionBy() {
            return PartitionBy.NONE;
        }

        @Override
        public boolean getSymbolCacheFlag(int columnIndex) {
            return configuration.getDefaultSymbolCacheFlag();
        }

        @Override
        public int getSymbolCapacity(int columnIndex) {
            return configuration.getDefaultSymbolCapacity();
        }

        @Override
        public CharSequence getTableName() {
            return cache.get(tableName);
        }

        @Override
        public int getTimestampIndex() {
            return timestampIndex;
        }

        TableStructureAdapter of(CharSequenceCache cache) {
            this.cache = cache;
            this.timestampIndex = columnNameType.size() / 2;
            this.columnCount = timestampIndex + 1;
            return this;
        }
    }
}

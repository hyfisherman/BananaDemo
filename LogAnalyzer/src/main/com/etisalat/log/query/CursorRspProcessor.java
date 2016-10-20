package com.etisalat.log.query;

import com.etisalat.log.common.JsonUtil;
import com.etisalat.log.common.LogQueryException;
import com.etisalat.log.common.WTType;
import com.etisalat.log.config.LogConfFactory;
import com.etisalat.log.parser.Cursor;
import com.etisalat.log.parser.QueryCondition;
import com.etisalat.log.sort.SortUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CursorRspProcessor implements RspProcess {
    protected static final Logger logger = LoggerFactory.getLogger(CursorRspProcessor.class);

    private QueryCondition queryCondition = null;
    private Cursor cursor = null;
    private long realReturnNum = 0;
    private long singleFileSize = 0;
    private String maxCollection = null;

    public CursorRspProcessor(QueryCondition queryCondition, Cursor cursor, long realReturnNum) {
        this.cursor = cursor;
        this.queryCondition = queryCondition;
        this.realReturnNum = realReturnNum;
        this.singleFileSize = SolrUtils.getSingleFileRecordNum(realReturnNum);
        List<String> collections = queryCondition.getCollections();
        this.maxCollection = collections.get(collections.size() - 1);
    }

    @Override
    public String process() throws LogQueryException {
        if (queryCondition.isExportOp()) {
            return processExport();
        } else {
            return processDisplay();
        }
    }

    @Override
    public String processExport() throws LogQueryException {
        String cacheKey = cursor.getCacheKey();
        long waitStart = System.currentTimeMillis();
        ResultCnt resultCnt = null;
        while (true) {
            checkTime(waitStart);
            resultCnt = QueryBatch.RESULTS_CNT_FOR_SHARDS.get(cacheKey);
            if (resultCnt == null) {
                throw new LogQueryException("no data to be downloaded");
            }
            if (resultCnt.getFetchNum() >= resultCnt.getTotalNum()) {
                break;
            }
        }

        Map<String, List<JsonObject>> resultMap = QueryBatch.RESULTS_FOR_SHARDS.get(cacheKey);
        if (resultMap == null) {
            String errMsg = "failed to query for null results.";
            logger.error(errMsg);
            throw new LogQueryException("errMsg");
        }

        try {
            if (WTType.JSON == queryCondition.getWtType()) {
                return writeJsonFiles(resultMap.entrySet());
            } else if (WTType.CSV == queryCondition.getWtType()) {
                return writeCSVFiles(resultMap.entrySet());
            } else if (WTType.XML == queryCondition.getWtType()) {
                return writeXmlFiles(resultMap.entrySet());
            } else {
                throw new LogQueryException("Unknown wtType.");
            }
        } catch (IOException e) {
            throw new LogQueryException("failed to download data, " + e.getMessage());
        }
    }

    private String getFileName() {
        return queryCondition.getLocalPath() + File.separator + "data-" + System.nanoTime() + "." + queryCondition
                .getWtType().name().toLowerCase();
    }

    private void write(String filePath, String content) throws IOException {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(new BufferedWriter(new FileWriter(filePath)));
            writer.write(content);
            writer.flush();
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private String writeJsonFiles(Set<Map.Entry<String, List<JsonObject>>> entrySet) throws IOException {
        JsonObject resultObj = SolrUtils.deepCopyJsonObj(LogConfFactory.solrQueryRspJsonObj);
        JsonObject rspJson = resultObj.getAsJsonObject("response");
        if (null == rspJson) {
            logger.error("rspJson is null and return null!");
            return null;
        }

        JsonArray rspJsonList = new JsonArray();
        rspJson.addProperty("nums", realReturnNum);
        List<JsonObject> jsonObjects = null;
        int docNum = 0;
        boolean finished = false;
        for (Map.Entry<String, List<JsonObject>> entry : entrySet) {
            if (finished) {
                break;
            }

            jsonObjects = entry.getValue();
            for (JsonObject jsonObj : jsonObjects) {
                if (docNum >= realReturnNum) {
                    finished = true;
                    break;
                }

                docNum++;

                rspJsonList.add(jsonObj);
                if (docNum % singleFileSize == 0) {
                    rspJson.add("docs", rspJsonList);
                    write(getFileName(), JsonUtil.toJson(resultObj));
                    rspJsonList = new JsonArray();
                }
            }
        }

        if (!finished && rspJsonList.size() != 0) {
            rspJson.add("docs", rspJsonList);
            write(getFileName(), JsonUtil.toJson(resultObj));
        }

        return queryCondition.getLocalPath();
    }

    private String writeJsonFiles(List<JsonObject> jsonObjects) throws IOException {
        JsonObject resultObj = SolrUtils.deepCopyJsonObj(LogConfFactory.solrQueryRspJsonObj);
        JsonObject rspJson = resultObj.getAsJsonObject("response");
        if (null == rspJson) {
            logger.error("rspJson is null and return null!");
            return null;
        }

        JsonArray rspJsonList = new JsonArray();
        rspJson.addProperty("nums", realReturnNum);
        int docNum = 0;
        boolean finished = false;
        for (JsonObject jsonObj : jsonObjects) {
            if (docNum >= realReturnNum) {
                finished = true;
                break;
            }

            docNum++;

            rspJsonList.add(jsonObj);
            if (docNum % singleFileSize == 0) {
                rspJson.add("docs", rspJsonList);
                write(getFileName(), JsonUtil.toJson(resultObj));
                rspJsonList = new JsonArray();
            }
        }

        if (!finished && rspJsonList.size() != 0) {
            rspJson.add("docs", rspJsonList);
            write(getFileName(), JsonUtil.toJson(resultObj));
        }

        return queryCondition.getLocalPath();
    }

    private String writeXmlFiles(Set<Map.Entry<String, List<JsonObject>>> entrySet) throws IOException {
        List<JsonObject> jsonObjects = null;
        int docNum = 0;
        XmlWriter xmlWriter = null;
        boolean finished = false;
        try {
            xmlWriter = XmlWriter.getWriter(getFileName(), realReturnNum);
            xmlWriter.writeStart();
            for (Map.Entry<String, List<JsonObject>> entry : entrySet) {
                if (finished) {
                    break;
                }

                jsonObjects = entry.getValue();
                for (JsonObject jsonObj : jsonObjects) {
                    if (docNum >= realReturnNum) {
                        finished = true;
                        break;
                    }

                    docNum++;
                    xmlWriter.writeXmlJson(jsonObj);
                    if (docNum % singleFileSize == 0) {
                        xmlWriter.writeEnd();
                        xmlWriter.flush();
                        xmlWriter.close();

                        if (docNum >= realReturnNum) {
                            finished = true;
                            break;
                        } else {
                            xmlWriter = XmlWriter.getWriter(getFileName(), realReturnNum);
                            xmlWriter.writeStart();
                        }
                    }
                }
            }
            if (!finished && docNum % singleFileSize != 0) {
                xmlWriter.writeEnd();
                xmlWriter.flush();
                xmlWriter.close();
            }
        } finally {
            if (xmlWriter != null && !xmlWriter.isClosed()) {
                xmlWriter.close();
            }
        }

        return queryCondition.getLocalPath();
    }

    private String writeXmlFiles(List<JsonObject> jsonObjects) throws IOException {
        XmlWriter xmlWriter = null;
        int docNum = 0;
        boolean finished = false;
        try {
            xmlWriter = XmlWriter.getWriter(getFileName(), realReturnNum);
            xmlWriter.writeStart();

            for (JsonObject jsonObj : jsonObjects) {
                if (docNum >= realReturnNum) {
                    finished = true;
                    break;
                }

                docNum++;
                xmlWriter.writeXmlJson(jsonObj);
                if (docNum % singleFileSize == 0) {
                    xmlWriter.writeEnd();
                    xmlWriter.flush();
                    xmlWriter.close();

                    if (docNum >= realReturnNum) {
                        finished = true;
                        break;
                    } else {
                        xmlWriter = XmlWriter.getWriter(getFileName(), realReturnNum);
                        xmlWriter.writeStart();
                    }
                }
            }

            if (!finished && docNum % singleFileSize != 0) {
                xmlWriter.writeEnd();
                xmlWriter.flush();
                xmlWriter.close();
            }
        } finally {
            if (xmlWriter != null && !xmlWriter.isClosed()) {
                xmlWriter.close();
            }
        }
        return queryCondition.getLocalPath();
    }

    private String writeCSVFiles(Set<Map.Entry<String, List<JsonObject>>> entrySet) throws IOException {
        String csvHeader = null;
        StringBuilder builder = new StringBuilder();
        List<JsonObject> jsonObjects = null;
        boolean first = true;
        int docNum = 0;
        boolean finished = false;
        for (Map.Entry<String, List<JsonObject>> entry : entrySet) {
            if (finished) {
                break;
            }

            jsonObjects = entry.getValue();
            for (JsonObject jsonObj : jsonObjects) {
                if (docNum >= realReturnNum) {
                    finished = true;
                    break;
                }

                docNum++;

                if (LogConfFactory.keepCsvHeader && csvHeader == null) {
                    csvHeader = SolrUtils.getCsvHeader(jsonObj);
                    builder.append(csvHeader).append("\n");
                }

                Set<Map.Entry<String, JsonElement>> set = jsonObj.getAsJsonObject().entrySet();
                first = true;
                for (Map.Entry<String, JsonElement> entryElement : set) {
                    if (first) {
                        builder.append(entryElement.getValue().getAsString());
                        first = false;
                    }
                    builder.append(",").append(entryElement.getValue().getAsString());
                }

                builder.append("\n");
                if (docNum % singleFileSize == 0) {
                    write(getFileName(), builder.toString());
                    builder.delete(0, builder.length());
                    if (LogConfFactory.keepCsvHeader) {
                        builder.append(csvHeader).append("\n");
                    }

                }
            }
        }

        if (!finished && builder.length() != 0) {
            write(getFileName(), builder.toString());
        }

        return queryCondition.getLocalPath();
    }

    private String writeCSVFiles(List<JsonObject> jsonObjectList) throws IOException {
        String csvHeader = null;
        StringBuilder builder = new StringBuilder();
        List<JsonObject> jsonObjects = null;
        boolean first = true;
        int docNum = 0;
        boolean finished = false;
        for (JsonObject jsonObj : jsonObjects) {
            if (docNum >= realReturnNum) {
                finished = true;
                break;
            }

            docNum++;

            if (LogConfFactory.keepCsvHeader && csvHeader == null) {
                csvHeader = SolrUtils.getCsvHeader(jsonObj);
                builder.append(csvHeader).append("\n");
            }
            Set<Map.Entry<String, JsonElement>> set = jsonObj.getAsJsonObject().entrySet();
            first = true;
            for (Map.Entry<String, JsonElement> entryElement : set) {
                if (first) {
                    builder.append(entryElement.getValue().getAsString());
                    first = false;
                }
                builder.append(",").append(entryElement.getValue().getAsString());
            }

            builder.append("\n");

            if (docNum % singleFileSize == 0) {
                write(getFileName(), builder.toString());
                builder.delete(0, builder.length());
                if (LogConfFactory.keepCsvHeader) {
                    builder.append(csvHeader).append("\n");
                }
            }
        }

        if (!finished && builder.length() != 0) {
            write(getFileName(), builder.toString());
        }

        return queryCondition.getLocalPath();
    }

    @Override
    public String processDisplay() throws LogQueryException {
        String cacheKey = cursor.getCacheKey();
        String collection = null;
        String collWithShardId = null;
        int idx = 0;

        long rows = queryCondition.getTotalNum();
        List<JsonObject> jsonObjectList = null;
        int cnt = 0;
        long waitStart = System.currentTimeMillis();
        JsonObject resultJsonObj = SolrUtils.deepCopyJsonObj(LogConfFactory.solrQueryRspJsonObj);
        JsonObject rspJsonObj = resultJsonObj.getAsJsonObject("response");
        JsonArray resultJsonObjArray = new JsonArray();
        int jsonListSize = 0;
        while (true) {
            checkTime(waitStart);
            Map<String, List<JsonObject>> resultMap = QueryBatch.RESULTS_FOR_SHARDS.get(cacheKey);
            if (cnt >= rows || cnt >= realReturnNum) {
                break;
            }

            if (resultMap == null) {
                try {
                    Thread.sleep(LogConfFactory.queryResultCheckInterval);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage());
                }
                continue;
            }

            collection = cursor.getCollection();
            collWithShardId = SolrUtils.getCollWithShardId(collection, cursor.getShardId());
            idx = cursor.getFetchIdx();

            jsonObjectList = resultMap.get(collWithShardId);

            if (jsonObjectList == null) {
                if (collection.equals(maxCollection) && collWithShardId.endsWith(LogConfFactory.solrMaxShardId)) {
                    break;
                }

                try {
                    Thread.sleep(LogConfFactory.queryResultCheckInterval);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage());
                }

                updateCursor(cursor, collection, collWithShardId, idx, 0);
                continue;
            }
            jsonListSize = jsonObjectList.size();
            for (; idx < jsonListSize; idx++) {
                if (cnt >= rows) {
                    break;
                }
                resultJsonObjArray.add(jsonObjectList.get(idx));
                cnt++;
            }

            updateCursor(cursor, collection, collWithShardId, idx, jsonListSize);
        }

        if (queryCondition.isSort()) {
            resultJsonObjArray = SortUtils.sortSingleShardRsp(queryCondition.getSortedFields(), resultJsonObjArray);
        }

        rspJsonObj.add("docs", resultJsonObjArray);
        rspJsonObj.addProperty("nums", realReturnNum);
        rspJsonObj.addProperty("nextCursorMark", cursor.toString());

        return JsonUtil.toJson(resultJsonObj);
    }

    private void checkTime(long waitStart) throws LogQueryException {
        long cost = System.currentTimeMillis() - waitStart;
        if (cost > LogConfFactory.queryTimeout) {
            String errMsg =
                    "query cost " + cost + "ms greater than " + LogConfFactory.queryTimeout + "ms, it is timeout";
            logger.error(errMsg);
            throw new LogQueryException(errMsg);
        }
    }

    private void updateCursor(Cursor cursor, String collection, String collWithShardId, int idx, int jsonListSize)
            throws LogQueryException {
        if (idx >= jsonListSize) {
            cursor.setFetchIdx(0);
            if (collWithShardId.endsWith(LogConfFactory.solrMaxShardId)) {
                collection = SolrUtils.getNextCollection(collection, maxCollection);
                cursor.setCollection(collection);
                cursor.setShardId(LogConfFactory.solrMinShardId);
            } else {
                cursor.setShardId(SolrUtils.getNextShardId(SolrUtils.getShardId(collWithShardId)));
            }
        } else {
            cursor.setFetchIdx(idx);
            cursor.setShardId(SolrUtils.getShardId(collWithShardId));
        }
    }
}

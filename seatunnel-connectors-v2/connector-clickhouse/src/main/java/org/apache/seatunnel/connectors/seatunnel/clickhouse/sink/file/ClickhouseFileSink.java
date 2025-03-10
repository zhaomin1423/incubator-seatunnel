/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.clickhouse.sink.file;

import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.CLICKHOUSE_LOCAL_PATH;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.COPY_METHOD;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.DATABASE;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.FIELDS;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.HOST;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.NODE_ADDRESS;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.NODE_PASS;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.PASSWORD;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.SHARDING_KEY;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.TABLE;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.USERNAME;

import org.apache.seatunnel.api.common.PrepareFailException;
import org.apache.seatunnel.api.sink.SeaTunnelSink;
import org.apache.seatunnel.api.sink.SinkWriter;
import org.apache.seatunnel.api.table.type.SeaTunnelDataType;
import org.apache.seatunnel.api.table.type.SeaTunnelRow;
import org.apache.seatunnel.api.table.type.SeaTunnelRowType;
import org.apache.seatunnel.common.config.CheckConfigUtil;
import org.apache.seatunnel.common.config.CheckResult;
import org.apache.seatunnel.common.constants.PluginType;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.config.ClickhouseFileCopyMethod;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.config.FileReaderOption;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.shard.Shard;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.shard.ShardMetadata;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.sink.client.ClickhouseProxy;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.state.CKAggCommitInfo;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.state.CKCommitInfo;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.state.ClickhouseSinkState;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.util.ClickhouseUtil;

import org.apache.seatunnel.shade.com.typesafe.config.Config;
import org.apache.seatunnel.shade.com.typesafe.config.ConfigFactory;

import com.clickhouse.client.ClickHouseNode;
import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@AutoService(SeaTunnelSink.class)
public class ClickhouseFileSink implements SeaTunnelSink<SeaTunnelRow, ClickhouseSinkState, CKCommitInfo, CKAggCommitInfo> {

    private FileReaderOption readerOption;

    @Override
    public String getPluginName() {
        return "ClickhouseFile";
    }

    @Override
    public void prepare(Config config) throws PrepareFailException {
        CheckResult checkResult = CheckConfigUtil.checkAllExists(config, HOST, TABLE, DATABASE, USERNAME, PASSWORD, CLICKHOUSE_LOCAL_PATH);
        if (!checkResult.isSuccess()) {
            throw new PrepareFailException(getPluginName(), PluginType.SINK, checkResult.getMsg());
        }
        Map<String, Object> defaultConfigs = ImmutableMap.<String, Object>builder()
                .put(COPY_METHOD, ClickhouseFileCopyMethod.SCP.getName())
                .build();

        config = config.withFallback(ConfigFactory.parseMap(defaultConfigs));
        List<ClickHouseNode> nodes = ClickhouseUtil.createNodes(config.getString(HOST),
                config.getString(DATABASE), config.getString(USERNAME), config.getString(PASSWORD));

        ClickhouseProxy proxy = new ClickhouseProxy(nodes.get(0));
        Map<String, String> tableSchema = proxy.getClickhouseTableSchema(config.getString(TABLE));
        String shardKey = null;
        String shardKeyType = null;
        if (config.hasPath(SHARDING_KEY)) {
            shardKey = config.getString(SHARDING_KEY);
            shardKeyType = tableSchema.get(shardKey);
        }
        ShardMetadata shardMetadata = new ShardMetadata(
                shardKey,
                shardKeyType,
                config.getString(DATABASE),
                config.getString(TABLE),
                false, // we don't need to set splitMode in clickhouse file mode.
                new Shard(1, 1, nodes.get(0)), config.getString(USERNAME), config.getString(PASSWORD));
        List<String> fields;
        if (config.hasPath(FIELDS)) {
            fields = config.getStringList(FIELDS);
            // check if the fields exist in schema
            for (String field : fields) {
                if (!tableSchema.containsKey(field)) {
                    throw new RuntimeException("Field " + field + " does not exist in table " + config.getString(TABLE));
                }
            }
        } else {
            fields = new ArrayList<>(tableSchema.keySet());
        }
        Map<String, String> nodeUser = config.getObjectList(NODE_PASS).stream()
                .collect(Collectors.toMap(configObject -> configObject.toConfig().getString(NODE_ADDRESS),
                    configObject -> configObject.toConfig().hasPath(USERNAME) ? configObject.toConfig().getString(USERNAME) : "root"));
        Map<String, String> nodePassword = config.getObjectList(NODE_PASS).stream()
                .collect(Collectors.toMap(configObject -> configObject.toConfig().getString(NODE_ADDRESS),
                    configObject -> configObject.toConfig().getString(PASSWORD)));

        proxy.close();
        this.readerOption = new FileReaderOption(shardMetadata, tableSchema, fields, config.getString(CLICKHOUSE_LOCAL_PATH),
                ClickhouseFileCopyMethod.from(config.getString(COPY_METHOD)), nodeUser, nodePassword);
    }

    @Override
    public void setTypeInfo(SeaTunnelRowType seaTunnelRowType) {
        this.readerOption.setSeaTunnelRowType(seaTunnelRowType);
    }

    @Override
    public SeaTunnelDataType<SeaTunnelRow> getConsumedType() {
        return this.readerOption.getSeaTunnelRowType();
    }

    @Override
    public SinkWriter<SeaTunnelRow, CKCommitInfo, ClickhouseSinkState> createWriter(SinkWriter.Context context) throws IOException {
        return new ClickhouseFileSinkWriter(readerOption, context);
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.core.sql;

import org.apache.seatunnel.core.base.utils.CommandLineUtils;
import org.apache.seatunnel.core.flink.args.FlinkCommandArgs;
import org.apache.seatunnel.core.flink.config.FlinkJobType;
import org.apache.seatunnel.core.sql.job.JobInfo;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class SqlVariableSubstitutionTest {
    public static final String TEST_RESOURCE_DIR = "/src/test/resources/";

    @Test
    public void normalizeStatementsWithMultiSqls() throws Exception {
        String[] args = {"-c", System.getProperty("user.dir") + TEST_RESOURCE_DIR + "flink.sql.conf.template",
            "-t", "-i", "table_name=events", "-i", "table_name2=print_table"};

        FlinkCommandArgs flinkArgs = CommandLineUtils.parse(args, new FlinkCommandArgs(), FlinkJobType.SQL.getType(), true);
        String configFilePath = flinkArgs.getConfigFile();
        String jobContent = FileUtils.readFileToString(new File(configFilePath), StandardCharsets.UTF_8);
        JobInfo jobInfo = new JobInfo(jobContent);
        Assertions.assertFalse(jobInfo.getJobContent().contains("events"));
        Assertions.assertFalse(jobInfo.getJobContent().contains("print_table"));

        jobInfo.substitute(flinkArgs.getVariables());
        Assertions.assertTrue(jobInfo.getJobContent().contains("events"));
        Assertions.assertTrue(jobInfo.getJobContent().contains("print_table"));
    }

}

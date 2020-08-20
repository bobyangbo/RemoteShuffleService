package com.uber.rss;

import com.uber.rss.clients.SingleServerReadClient;
import com.uber.rss.clients.RecordKeyValuePair;
import com.uber.rss.clients.SingleServerWriteClient;
import com.uber.rss.common.AppTaskAttemptId;
import com.uber.rss.testutil.ClientTestUtils;
import com.uber.rss.testutil.StreamServerTestUtils;
import com.uber.rss.testutil.TestConstants;
import com.uber.rss.testutil.TestStreamServer;
import com.uber.rss.util.ThreadUtils;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StreamServerMultiAttemptTest {
    @DataProvider(name = "data-provider")
    public Object[][] dataProviderMethod() {
        return new Object[][] { {false}, {true} };
    }

    @Test(dataProvider = "data-provider")
    public void readRecordsInLastTaskAttempt(boolean waitShuffleFileClosed) {
        TestStreamServer testServer = TestStreamServer.createRunningServer();

        int numMaps = 1;
        AppTaskAttemptId appTaskAttemptId1 = new AppTaskAttemptId("app1", "exec1", 1, 2, 0L);
        AppTaskAttemptId appTaskAttemptId2 = new AppTaskAttemptId(appTaskAttemptId1.getAppMapId(), 1L);

        List<SingleServerWriteClient> writeClientsToClose = new ArrayList<>();

        try {
            // Write with taskAttemptId=0
            {
                SingleServerWriteClient writeClient = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId1.getAppId(), appTaskAttemptId1.getAppAttempt());
                writeClientsToClose.add(writeClient);

                writeClient.startUpload(appTaskAttemptId1, numMaps, 20);

                writeClient.sendRecord(1, null, null);

                writeClient.sendRecord(2,
                        ByteBuffer.wrap(new byte[0]),
                        ByteBuffer.wrap(new byte[0]));

                writeClient.sendRecord(3,
                        ByteBuffer.wrap("key1".getBytes(StandardCharsets.UTF_8)),
                        ByteBuffer.wrap("value1".getBytes(StandardCharsets.UTF_8)));

                writeClient.finishUpload();
            }

            List<RecordKeyValuePair> records;

            if (waitShuffleFileClosed) {
                testServer.pollAndWaitShuffleFilesClosed(appTaskAttemptId1.getAppShuffleId(), TestConstants.DATA_AVAILABLE_TIMEOUT);

                records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 1, Arrays.asList(appTaskAttemptId1.getTaskAttemptId()));
                Assert.assertEquals(records.size(), 1);
            }

            // Write with taskAttemptId=1
            {
                SingleServerWriteClient writeClient = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId1.getAppId(), appTaskAttemptId1.getAppAttempt());
                writeClientsToClose.add(writeClient);

                writeClient.startUpload(appTaskAttemptId2, numMaps, 20);

                writeClient.sendRecord(2, null, null);

                writeClient.sendRecord(9,
                        ByteBuffer.wrap("key9".getBytes(StandardCharsets.UTF_8)),
                        ByteBuffer.wrap("value9".getBytes(StandardCharsets.UTF_8)));

                writeClient.finishUpload();
            }

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 1, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 2, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 1);
            Assert.assertEquals(records.get(0).getKey(), null);
            Assert.assertEquals(records.get(0).getValue(), null);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 3, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 9, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 1);
            
            RecordKeyValuePair record = records.get(0);
            Assert.assertEquals(new String(record.getKey(), StandardCharsets.UTF_8), "key9");
            Assert.assertEquals(new String(record.getValue(), StandardCharsets.UTF_8), "value9");
        } finally {
            writeClientsToClose.forEach(SingleServerWriteClient::close);
            testServer.shutdown();
        }
    }

    @Test
    public void singleMapperCloseAndOpenSamePartition() {
        TestStreamServer testServer = TestStreamServer.createRunningServer();

        AppTaskAttemptId appTaskAttemptId1 = new AppTaskAttemptId("app1", "exec1", 1, 2, 0L);
        AppTaskAttemptId appTaskAttemptId2 = new AppTaskAttemptId(appTaskAttemptId1.getAppMapId(), 1L);

        List<SingleServerWriteClient> writeClientsToClose = new ArrayList<>();

        try {
            // Write with taskAttemptId=0
            {
                SingleServerWriteClient writeClient = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId1.getAppId(), appTaskAttemptId1.getAppAttempt());
                writeClientsToClose.add(writeClient);

                writeClient.startUpload(appTaskAttemptId1, 1, 20);

                writeClient.sendRecord(1, null, null);

                writeClient.sendRecord(2,
                        ByteBuffer.wrap(new byte[0]),
                        ByteBuffer.wrap(new byte[0]));

                writeClient.sendRecord(3,
                        ByteBuffer.wrap("key1".getBytes(StandardCharsets.UTF_8)),
                        ByteBuffer.wrap("value1".getBytes(StandardCharsets.UTF_8)));

                writeClient.finishUpload();
            }

            // Write with taskAttemptId=1
            {
                SingleServerWriteClient writeClient = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId1.getAppId(), appTaskAttemptId1.getAppAttempt());
                writeClientsToClose.add(writeClient);

                writeClient.startUpload(appTaskAttemptId2, 1, 20);

                writeClient.sendRecord(3,
                        ByteBuffer.wrap("key3_1".getBytes(StandardCharsets.UTF_8)),
                        ByteBuffer.wrap("value3_1".getBytes(StandardCharsets.UTF_8)));

                writeClient.finishUpload();
            }

            List<RecordKeyValuePair> records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 1, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 2, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 3, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 1);

            RecordKeyValuePair record = records.get(0);
            Assert.assertEquals(new String(record.getKey(), StandardCharsets.UTF_8), "key3_1");
            Assert.assertEquals(new String(record.getValue(), StandardCharsets.UTF_8), "value3_1");
        } finally {
            writeClientsToClose.forEach(SingleServerWriteClient::close);
            testServer.shutdown();
        }
    }

    @Test
    public void singleMapperOverwriteSamePartitionWithoutClose() {
        TestStreamServer testServer = TestStreamServer.createRunningServer();

        AppTaskAttemptId appTaskAttemptId1 = new AppTaskAttemptId("app1", "exec1", 1, 2, 0L);

        List<SingleServerWriteClient> writeClientsToClose = new ArrayList<>();

        try {
            // Write with taskAttemptId=0
            {
                SingleServerWriteClient writeClient = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId1.getAppId(), appTaskAttemptId1.getAppAttempt());
                writeClientsToClose.add(writeClient);

                writeClient.startUpload(appTaskAttemptId1, 1, 20);

                writeClient.sendRecord(1, null, null);

                writeClient.sendRecord(2,
                        ByteBuffer.wrap(new byte[0]),
                        ByteBuffer.wrap(new byte[0]));

                writeClient.sendRecord(3,
                        ByteBuffer.wrap("key1".getBytes(StandardCharsets.UTF_8)),
                        ByteBuffer.wrap("value1".getBytes(StandardCharsets.UTF_8)));
            }

            // Write with taskAttemptId=1
            AppTaskAttemptId appTaskAttemptId2 = new AppTaskAttemptId(appTaskAttemptId1.getAppMapId(), 1L);
            {
                SingleServerWriteClient writeClient = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId2.getAppId(), appTaskAttemptId2.getAppAttempt());
                writeClientsToClose.add(writeClient);

                writeClient.startUpload(appTaskAttemptId2, 1, 20);

                writeClient.sendRecord(3,
                        ByteBuffer.wrap("key3_1".getBytes(StandardCharsets.UTF_8)),
                        ByteBuffer.wrap("value3_1".getBytes(StandardCharsets.UTF_8)));

                writeClient.finishUpload();

                StreamServerTestUtils.waitTillDataAvailable(testServer.getShufflePort(), appTaskAttemptId2.getAppShuffleId(), Arrays.asList(3), Arrays.asList(appTaskAttemptId2.getTaskAttemptId()), false);
            }

            List<RecordKeyValuePair> records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 1, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 2, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 3, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 1);

            RecordKeyValuePair record = records.get(0);
            Assert.assertEquals(new String(record.getKey(), StandardCharsets.UTF_8), "key3_1");
            Assert.assertEquals(new String(record.getValue(), StandardCharsets.UTF_8), "value3_1");
        } finally {
            writeClientsToClose.forEach(SingleServerWriteClient::close);
            testServer.shutdown();
        }
    }

    @Test
    public void singleMapperWriteDataWithOldTaskAttemptId() {
        TestStreamServer testServer = TestStreamServer.createRunningServer();

        AppTaskAttemptId appTaskAttemptId1 = new AppTaskAttemptId("app1", "exec1", 1, 2, 0L);

        List<SingleServerWriteClient> writeClientsToClose = new ArrayList<>();

        try {
            // Write with taskAttemptId=0
            SingleServerWriteClient writeClient1 = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId1.getAppId(), appTaskAttemptId1.getAppAttempt());
            writeClientsToClose.add(writeClient1);

            writeClient1.startUpload(appTaskAttemptId1, 1, 20);

            writeClient1.sendRecord(1, null, null);

            writeClient1.sendRecord(2,
                    ByteBuffer.wrap(new byte[0]),
                    ByteBuffer.wrap(new byte[0]));

            writeClient1.sendRecord(3,
                    ByteBuffer.wrap("key1".getBytes(StandardCharsets.UTF_8)),
                    ByteBuffer.wrap("value1".getBytes(StandardCharsets.UTF_8)));

            // Write with taskAttemptId=1
            AppTaskAttemptId appTaskAttemptId2 = new AppTaskAttemptId(appTaskAttemptId1.getAppMapId(), 1L);
            SingleServerWriteClient writeClient2 = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId2.getAppId(), appTaskAttemptId2.getAppAttempt());
            writeClientsToClose.add(writeClient2);

            writeClient2.startUpload(appTaskAttemptId2, 1, 20);

            writeClient2.sendRecord(3,
                    ByteBuffer.wrap("key3_1".getBytes(StandardCharsets.UTF_8)),
                    ByteBuffer.wrap("value3_1".getBytes(StandardCharsets.UTF_8)));

            writeClient2.finishUpload();

            StreamServerTestUtils.waitTillDataAvailable(testServer.getShufflePort(), appTaskAttemptId2.getAppShuffleId(), Arrays.asList(1, 2, 3), Arrays.asList(appTaskAttemptId2.getTaskAttemptId()), false);

            // Write with taskAttemptId=0 again
            writeClient1.sendRecord(3,
                    ByteBuffer.wrap("key1".getBytes(StandardCharsets.UTF_8)),
                    ByteBuffer.wrap("value1".getBytes(StandardCharsets.UTF_8)));
            try {
                writeClient1.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // Read records and verify
            List<RecordKeyValuePair> records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 1, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 2, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId1.getAppShuffleId(), 3, Arrays.asList(appTaskAttemptId2.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 1);

            RecordKeyValuePair record = records.get(0);
            Assert.assertEquals(new String(record.getKey(), StandardCharsets.UTF_8), "key3_1");
            Assert.assertEquals(new String(record.getValue(), StandardCharsets.UTF_8), "value3_1");
        } finally {
            writeClientsToClose.forEach(SingleServerWriteClient::close);
            testServer.shutdown();
        }
    }

    @Test
    public void staleTaskAttemptWritingData() {
        TestStreamServer testServer = TestStreamServer.createRunningServer();

        int numMaps = 1;
        AppTaskAttemptId appTaskAttemptId = new AppTaskAttemptId("app1", "exec1", 1, 2, 1L);

        try {
            // Write with taskAttemptId=1
            try (SingleServerWriteClient writeClient = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId.getAppId(), appTaskAttemptId.getAppAttempt())) {
                writeClient.startUpload(appTaskAttemptId, numMaps, 20);

                writeClient.sendRecord(9,
                        ByteBuffer.wrap("key9".getBytes(StandardCharsets.UTF_8)),
                        ByteBuffer.wrap("value9".getBytes(StandardCharsets.UTF_8)));

                writeClient.finishUpload();

                StreamServerTestUtils.waitTillDataAvailable(testServer.getShufflePort(), appTaskAttemptId.getAppShuffleId(), Arrays.asList(9), Arrays.asList(appTaskAttemptId.getTaskAttemptId()), false);
            }
            
            // Write stale attempt with taskAttemptId=0
            try (SingleServerWriteClient writeClient = ClientTestUtils.getOrCreateWriteClient(testServer.getShufflePort(), appTaskAttemptId.getAppId(), appTaskAttemptId.getAppAttempt())) {
                writeClient.startUpload(new AppTaskAttemptId(appTaskAttemptId.getAppMapId(), 0L), numMaps, 20);

                writeClient.sendRecord(1, null, null);

                writeClient.sendRecord(2,
                        ByteBuffer.wrap(new byte[0]),
                        ByteBuffer.wrap(new byte[0]));

                writeClient.sendRecord(3,
                        ByteBuffer.wrap("key1".getBytes(StandardCharsets.UTF_8)),
                        ByteBuffer.wrap("value1".getBytes(StandardCharsets.UTF_8)));

                writeClient.finishUpload();
            }

            List<RecordKeyValuePair> records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId.getAppShuffleId(), 1, Arrays.asList(appTaskAttemptId.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId.getAppShuffleId(), 2, Arrays.asList(appTaskAttemptId.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId.getAppShuffleId(), 3, Arrays.asList(appTaskAttemptId.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 0);

            records = StreamServerTestUtils.readAllRecords(testServer.getShufflePort(), appTaskAttemptId.getAppShuffleId(), 9, Arrays.asList(appTaskAttemptId.getTaskAttemptId()));
            Assert.assertEquals(records.size(), 1);

            RecordKeyValuePair record = records.get(0);
            Assert.assertEquals(new String(record.getKey(), StandardCharsets.UTF_8), "key9");
            Assert.assertEquals(new String(record.getValue(), StandardCharsets.UTF_8), "value9");
        } finally {
            testServer.shutdown();
        }
    }
}
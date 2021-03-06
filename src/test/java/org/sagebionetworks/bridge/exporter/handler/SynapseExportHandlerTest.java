package org.sagebionetworks.bridge.exporter.handler;


import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.notNull;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.SetMultimap;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.config.Config;
import org.sagebionetworks.bridge.exporter.helper.BridgeHelper;
import org.sagebionetworks.bridge.exporter.helper.BridgeHelperTest;
import org.sagebionetworks.bridge.exporter.metrics.Metrics;
import org.sagebionetworks.bridge.exporter.request.BridgeExporterRequest;
import org.sagebionetworks.bridge.exporter.synapse.ColumnDefinition;
import org.sagebionetworks.bridge.exporter.synapse.SynapseHelper;
import org.sagebionetworks.bridge.exporter.synapse.TransferMethod;
import org.sagebionetworks.bridge.exporter.util.BridgeExporterUtil;
import org.sagebionetworks.bridge.exporter.util.TestUtil;
import org.sagebionetworks.bridge.exporter.worker.ExportSubtask;
import org.sagebionetworks.bridge.exporter.worker.ExportTask;
import org.sagebionetworks.bridge.exporter.worker.ExportWorkerManager;
import org.sagebionetworks.bridge.exporter.worker.TsvInfo;
import org.sagebionetworks.bridge.file.InMemoryFileHelper;
import org.sagebionetworks.bridge.json.DefaultObjectMapper;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadFieldType;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.schema.UploadSchemaKey;

public class SynapseExportHandlerTest {
    private static final List<ColumnDefinition> MOCK_COLUMN_DEFINITION;

    private static final List<ColumnModel> MOCK_COLUMN_LIST;

    static {
        MOCK_COLUMN_DEFINITION = createTestSynapseColumnDefinitions();
        MOCK_COLUMN_LIST = createTestSynapseColumnList(MOCK_COLUMN_DEFINITION);
    }

    // Constants needed to create metadata (phone info, app version)
    private static final String DUMMY_USER_SHARING_SCOPE = "ALL_QUALIFIED_RESEARCHERS";
    private static final String DUMMY_APP_VERSION = "Bridge-EX 2.0";
    private static final String DUMMY_PHONE_INFO = "My Debugger";
    private static final String DUMMY_METADATA_JSON_TEXT = "{\n" +
            "   \"appVersion\":\"" + DUMMY_APP_VERSION +"\",\n" +
            "   \"phoneInfo\":\"" + DUMMY_PHONE_INFO + "\"\n" +
            "}";

    // Constants needed to create a record
    private static final long DUMMY_CREATED_ON = 7777777;
    private static final Set<String> DUMMY_DATA_GROUPS = ImmutableSet.of("foo", "bar", "baz");
    private static final String DUMMY_DATA_GROUPS_FLATTENED = "bar,baz,foo";
    private static final String DUMMY_SUBSTUDY_MEMBERSHIPS = "|subA=extA|subB=|";
    private static final String DUMMY_HEALTH_CODE = "dummy-health-code";
    private static final String DUMMY_RECORD_ID = "dummy-record-id";

    // Constants to make a request.
    public static final LocalDate DUMMY_REQUEST_DATE = LocalDate.parse("2015-10-31");
    private static final DateTime DUMMY_REQUEST_DATE_TIME = DateTime.parse("2015-10-31T23:59:59Z");
    public static final BridgeExporterRequest DUMMY_REQUEST = new BridgeExporterRequest.Builder()
            .withEndDateTime(DUMMY_REQUEST_DATE_TIME).withUseLastExportTime(true).build();

    // Constants to make a schema.
    public static final String TEST_STUDY_ID = "my-study";
    public static final String TEST_SCHEMA_ID = "my-schema";
    public static final int TEST_SCHEMA_REV = 1;
    public static final UploadSchemaKey DUMMY_SCHEMA_KEY = new UploadSchemaKey.Builder().withAppId(TEST_STUDY_ID)
            .withSchemaId(TEST_SCHEMA_ID).withRevision(TEST_SCHEMA_REV).build();

    // Misc test constants. Some are shared with other tests.
    public static final String DUMMY_DDB_PREFIX = "unittest-exporter-";
    private static final String DUMMY_EXTERNAL_ID = "unsanitized external id";
    public static final Study DUMMY_STUDY = new Study().identifier(TEST_STUDY_ID).uploadMetadataFieldDefinitions(null);
    public static final String RAW_DATA_ATTACHMENT_ID = "my-raw.zip";
    public static final String RAW_DATA_FILEHANDLE_ID = "my-raw-data-filehandle";
    public static final long TEST_SYNAPSE_DATA_ACCESS_TEAM_ID = 1337;
    public static final int TEST_SYNAPSE_PRINCIPAL_ID = 123456;
    public static final String TEST_SYNAPSE_PROJECT_ID = "test-synapse-project-id";
    public static final String TEST_SYNAPSE_TABLE_ID = "test-synapse-table-id";

    private static final List<String> COMMON_COLUMN_NAME_LIST = ImmutableList.of("recordId", "appVersion", "phoneInfo",
            "uploadDate", "healthCode", "externalId", "dataGroups", "substudyMemberships", "createdOn", "userSharingScope");
    private static final List<String> COMMON_COLUMN_VALUE_LIST = ImmutableList.of(DUMMY_RECORD_ID, DUMMY_APP_VERSION,
            DUMMY_PHONE_INFO, DUMMY_REQUEST_DATE.toString(), DUMMY_HEALTH_CODE, DUMMY_EXTERNAL_ID,
            DUMMY_DATA_GROUPS_FLATTENED, DUMMY_SUBSTUDY_MEMBERSHIPS, String.valueOf(DUMMY_CREATED_ON), DUMMY_USER_SHARING_SCOPE);

    private ExportWorkerManager manager;
    private InMemoryFileHelper mockFileHelper;
    private SynapseHelper mockSynapseHelper;
    private byte[] tsvBytes;
    private ExportTask task;

    @BeforeMethod
    public void before() {
        // clear tsvBytes, because TestNG doesn't always do that
        tsvBytes = null;
    }

    private void setup(SynapseExportHandler handler) throws Exception {
        setupWithSchema(handler, null, null);
    }

    private void setupWithSchema(SynapseExportHandler handler, UploadSchemaKey schemaKey, UploadSchema schema)
    throws Exception {
        // This needs to be done first, because lots of stuff reference this, even while we're setting up mocks.
        handler.setStudyId(TEST_STUDY_ID);

        // mock BridgeHelper
        BridgeHelper mockBridgeHelper = mock(BridgeHelper.class);
        when(mockBridgeHelper.getSchema(any(), eq(schemaKey))).thenReturn(schema);
        when(mockBridgeHelper.getStudy(TEST_STUDY_ID)).thenReturn(DUMMY_STUDY);

        // mock config
        Config mockConfig = mock(Config.class);
        when(mockConfig.get(ExportWorkerManager.CONFIG_KEY_EXPORTER_DDB_PREFIX)).thenReturn(DUMMY_DDB_PREFIX);
        when(mockConfig.getInt(ExportWorkerManager.CONFIG_KEY_SYNAPSE_PRINCIPAL_ID))
                .thenReturn(TEST_SYNAPSE_PRINCIPAL_ID);
        when(mockConfig.getInt(ExportWorkerManager.CONFIG_KEY_WORKER_MANAGER_PROGRESS_REPORT_PERIOD)).thenReturn(250);

        // mock file helper
        mockFileHelper = new InMemoryFileHelper();
        File tmpDir = mockFileHelper.createTempDir();

        // Mock Synapse Helper - We'll fill in the behavior later, because due to the way this test is constructed, we
        // need to set up the Manager before we can properly mock the Synapse Helper.
        mockSynapseHelper = mock(SynapseHelper.class);

        // setup manager - This is mostly used to get helper objects.
        manager = spy(new ExportWorkerManager());
        manager.setBridgeHelper(mockBridgeHelper);
        manager.setConfig(mockConfig);
        manager.setFileHelper(mockFileHelper);
        manager.setSynapseHelper(mockSynapseHelper);
        manager.setSynapseColumnDefinitions(MOCK_COLUMN_DEFINITION);
        handler.setManager(manager);

        // set up task
        task = new ExportTask.Builder().withExporterDate(DUMMY_REQUEST_DATE).withMetrics(new Metrics())
                .withRequest(DUMMY_REQUEST).withTmpDir(tmpDir).build();

        // mock Synapse helper
        List<ColumnModel> columnModelList = new ArrayList<>();
        columnModelList.addAll(MOCK_COLUMN_LIST);
        columnModelList.addAll(handler.getSynapseTableColumnList(task));
        when(mockSynapseHelper.getColumnModelsForTableWithRetry(TEST_SYNAPSE_TABLE_ID)).thenReturn(columnModelList);

        // mock serializeToSynapseType() - We actually call through to the real method, but we mock out the underlying
        // uploadFromS3ToSynapseFileHandle() to avoid hitting real back-ends.
        when(mockSynapseHelper.serializeToSynapseType(any(), any(), any(), any(), any(), any(), any()))
                .thenCallRealMethod();
        when(mockSynapseHelper.uploadFromS3ToSynapseFileHandle(TEST_SYNAPSE_PROJECT_ID, RAW_DATA_ATTACHMENT_ID))
                .thenReturn(RAW_DATA_FILEHANDLE_ID);

        // spy StudyInfo getters
        // These calls through to a bunch of stuff (which we test in ExportWorkerManagerTest), so to simplify our test,
        // we just use a spy here.
        doReturn(TEST_SYNAPSE_PROJECT_ID).when(manager).getSynapseProjectIdForStudyAndTask(eq(TEST_STUDY_ID),
                same(task));
        doReturn(false).when(manager).isStudyIdExcludedInExportForStudy(SynapseExportHandlerTest.TEST_STUDY_ID);
        doReturn(TEST_SYNAPSE_DATA_ACCESS_TEAM_ID).when(manager).getDataAccessTeamIdForStudy(TEST_STUDY_ID);

        // Similarly, spy get/setSynapseTableIdFromDDB.
        doReturn(TEST_SYNAPSE_TABLE_ID).when(manager).getSynapseTableIdFromDdb(task, handler.getDdbTableName(),
                handler.getDdbTableKeyName(), handler.getDdbTableKeyValue());
    }

    @Test
    public void normalCase() throws Exception {
        // This test case will attempt to write 3 rows:
        //   write a line
        //   write error
        //   write 2nd line after error

        SynapseExportHandler handler = new TestSynapseHandler();
        setup(handler);
        mockSynapseHelperUploadTsv(2);

        // make subtasks
        ExportSubtask subtask1 = makeSubtask(task, "foo", "normal first record");
        ExportSubtask subtask2 = makeSubtask(task, "error", "error second record");
        ExportSubtask subtask3 = makeSubtask(task, "foo", "normal third record");

        // execute
        handler.handle(subtask1);
        try {
            handler.handle(subtask2);
            fail("expected exception");
        } catch (IOException ex) {
            assertEquals(ex.getMessage(), "error second record");
        }
        handler.handle(subtask3);
        handler.uploadToSynapseForTask(task);

        // validate tsv file
        List<String> tsvLineList = TestUtil.bytesToLines(tsvBytes);
        assertEquals(tsvLineList.size(), 3);
        validateTsvHeaders(tsvLineList.get(0), "foo");
        validateTsvRow(tsvLineList.get(1), "normal first record");
        validateTsvRow(tsvLineList.get(2), "normal third record");

        // validate metrics
        Multiset<String> counterMap = task.getMetrics().getCounterMap();
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".lineCount"), 2);
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".errorCount"), 1);

        // validate tsvInfo
        TsvInfo tsvInfo = handler.getTsvInfoForTask(task);
        assertEquals(tsvInfo.getLineCount(), 2);
        assertNotNull(tsvInfo.getRecordIds());
        List<String> recordIds = tsvInfo.getRecordIds();
        assertEquals(recordIds.size(), 2);
        for (String recordId : recordIds) {
            assertEquals(recordId, DUMMY_RECORD_ID);
        }

        postValidation();
    }

    private static class FilenameTestSynapseHandler extends TestSynapseHandler {
        @Override
        protected String getDdbTableKeyValue() {
            // We only accept alphanumeric characters, dashes, and underscores, and filter out everything else.
            return "CAPITAL_lowercase_remove+ .!@#$between_123_with-dash";
        }
    }

    @Test
    public void tsvFilenameTest() throws Exception {
        SynapseExportHandler handler = new FilenameTestSynapseHandler();
        setup(handler);
        mockSynapseHelperUploadTsv(1);

        // make subtasks
        ExportSubtask subtask = makeSubtask(task, "foo", "test record");

        // execute
        handler.handle(subtask);
        handler.uploadToSynapseForTask(task);

        // We validate the TSV and metrics somewhere else. This test is just to test the filename.
        String expectedFilenamePrefix = "CAPITAL_lowercase_removebetween_123_with-dash";
        TsvInfo tsvInfo = handler.getTsvInfoForTask(task);
        File tsvFile = tsvInfo.getFile();
        assertTrue(tsvFile.getName().startsWith(expectedFilenamePrefix));
        assertTrue(tsvFile.getName().endsWith(".tsv"));

        verify(mockSynapseHelper).uploadTsvFileToTable(any(), any(), same(tsvFile));

        // As a sanity check, verify that the TSV is non-empty and contains the our subtask.
        String tsvString = new String(tsvBytes, Charsets.UTF_8);
        assertTrue(tsvString.contains("foo"));
        assertTrue(tsvString.contains("test record"));
    }

    @Test
    public void noRows() throws Exception {
        SynapseExportHandler handler = new TestSynapseHandler();
        setup(handler);

        // execute - We never call the handler with any rows.
        handler.uploadToSynapseForTask(task);

        // verify we don't upload the TSV to Synapse
        verify(mockSynapseHelper, never()).uploadTsvFileToTable(any(), any(), any());

        // validate metrics
        Multiset<String> counterMap = task.getMetrics().getCounterMap();
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".lineCount"), 0);
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".errorCount"), 0);

        // validate tsvInfo
        assertNull(handler.getTsvInfoForTask(task));

        postValidation();
    }

    @Test
    public void errorsOnly() throws Exception {
        SynapseExportHandler handler = new TestSynapseHandler();
        setup(handler);

        // make subtasks
        ExportSubtask subtask1 = makeSubtask(task, "error", "first error");
        ExportSubtask subtask2 = makeSubtask(task, "error", "second error");

        // execute
        try {
            handler.handle(subtask1);
            fail("expected exception");
        } catch (IOException ex) {
            assertEquals(ex.getMessage(), "first error");
        }

        try {
            handler.handle(subtask2);
            fail("expected exception");
        } catch (IOException ex) {
            assertEquals(ex.getMessage(), "second error");
        }

        handler.uploadToSynapseForTask(task);

        // verify we don't upload the TSV to Synapse
        verify(mockSynapseHelper, never()).uploadTsvFileToTable(any(), any(), any());

        // validate metrics
        Multiset<String> counterMap = task.getMetrics().getCounterMap();
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".lineCount"), 0);
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".errorCount"), 2);

        // validate tsvInfo
        TsvInfo tsvInfo = handler.getTsvInfoForTask(task);
        assertEquals(tsvInfo.getLineCount(), 0);
        assertEquals(tsvInfo.getRecordIds().size(), 0);

        postValidation();
    }

    @Test
    public void appVersionExportHandlerTest() throws Exception {
        SynapseExportHandler handler = new AppVersionExportHandler();
        setup(handler);
        mockSynapseHelperUploadTsv(2);

        // execute
        handler.handle(makeSubtask(task, "{}"));
        handler.handle(makeSubtaskWithSchema(task, null, "{}"));
        handler.uploadToSynapseForTask(task);

        // validate tsv file
        List<String> tsvLineList = TestUtil.bytesToLines(tsvBytes);
        assertEquals(tsvLineList.size(), 3);
        validateTsvHeaders(tsvLineList.get(0), "originalTable");
        validateTsvRow(tsvLineList.get(1), DUMMY_SCHEMA_KEY.toString());
        validateTsvRow(tsvLineList.get(2), BridgeExporterUtil.DEFAULT_TABLE_NAME);

        // validate metrics
        Metrics metrics = task.getMetrics();

        Multiset<String> counterMap = metrics.getCounterMap();
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".lineCount"), 2);
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".errorCount"), 0);

        SetMultimap<String, String> keyValuesMap = metrics.getKeyValuesMap();
        Set<String> uniqueAppVersionSet = keyValuesMap.get("uniqueAppVersions[" + TEST_STUDY_ID + "]");
        assertTrue(uniqueAppVersionSet.contains(DUMMY_APP_VERSION));

        // validate tsvInfo
        TsvInfo tsvInfo = handler.getTsvInfoForTask(task);
        assertEquals(tsvInfo.getLineCount(), 2);

        postValidation();
    }

    @Test
    public void appVersionExportHandlerStudyIdExcludedTest() throws Exception {
        SynapseExportHandler handler = new AppVersionExportHandler();
        setup(handler);
        mockSynapseHelperUploadTsv(1);

        // flip the studyIdExcludedInExport flag to true
        doReturn(true).when(manager).isStudyIdExcludedInExportForStudy(TEST_STUDY_ID);

        // execute
        handler.handle(makeSubtask(task, "{}"));
        handler.uploadToSynapseForTask(task);

        // validate tsv file
        List<String> tsvLineList = TestUtil.bytesToLines(tsvBytes);
        assertEquals(tsvLineList.size(), 2);
        validateTsvHeaders(tsvLineList.get(0), "originalTable");
        validateTsvRow(tsvLineList.get(1), TEST_SCHEMA_ID + "-v" + TEST_SCHEMA_REV);

        // Don't bother validating metrics, tsvInfo, or anything else. That's tested above.
    }

    @Test
    public void schemaBasedExportHandlerTest() throws Exception {
        // We don't need to exhaustively test all column types, as a lot of it is baked into
        // SynapseHelper.BRIDGE_TYPE_TO_SYNAPSE_TYPE. We just need to test multi_choice, timestamp,
        // int (non-string), short string, long string (large text aka blob), freeform text -> attachment,
        // large text attachments
        UploadSchema testSchema = BridgeHelperTest.simpleSchemaBuilder().studyId(TEST_STUDY_ID)
                .schemaId(TEST_SCHEMA_ID).revision((long) TEST_SCHEMA_REV).fieldDefinitions(ImmutableList.of(
                        new UploadFieldDefinition().name("foo").type(UploadFieldType.STRING).maxLength(20),
                        new UploadFieldDefinition().name("foooo").type(UploadFieldType.STRING).maxLength(9999),
                        new UploadFieldDefinition().name("unbounded-foo").type(UploadFieldType.STRING)
                                .unboundedText(true),
                        new UploadFieldDefinition().name("bar").type(UploadFieldType.INT),
                        new UploadFieldDefinition().name("submitTime").type(UploadFieldType.TIMESTAMP),
                        new UploadFieldDefinition().name("sports").type(UploadFieldType.MULTI_CHOICE)
                                .multiChoiceAnswerList(ImmutableList.of("fencing", "football", "running", "swimming")),
                        new UploadFieldDefinition().name("delicious").type(UploadFieldType.MULTI_CHOICE)
                                .multiChoiceAnswerList(ImmutableList.of("Yes", "No")).allowOtherChoices(true),
                        new UploadFieldDefinition().name("my-large-text-attachment")
                                .type(UploadFieldType.LARGE_TEXT_ATTACHMENT)));
        UploadSchemaKey testSchemaKey = BridgeExporterUtil.getSchemaKeyFromSchema(testSchema);

        // Set up handler and test. setSchema() needs to be called before setup, since a lot of the stuff in the
        // handler depends on it, even while we're mocking stuff.
        SchemaBasedExportHandler handler = new SchemaBasedExportHandler();
        handler.setSchemaKey(testSchemaKey);
        setupWithSchema(handler, testSchemaKey, testSchema);
        mockSynapseHelperUploadTsv(1);

        // Mock downloadLargeTextAttachment()
        when(mockSynapseHelper.downloadLargeTextAttachment("my-large-text-attachment-id")).thenReturn(
                "This is my large text attachment");

        // make subtasks
        String submitTimeStr = "2016-06-09T15:54+0900";
        long submitTimeMillis = DateTime.parse(submitTimeStr).getMillis();
        String recordJsonText = "{\n" +
                "   \"foo\":\"This is a string.\",\n" +
                "   \"foooo\":\"Example (not) long string\",\n" +
                "   \"unbounded-foo\":\"Potentially unbounded string\",\n" +
                "   \"bar\":42,\n" +
                "   \"submitTime\":\"" + submitTimeStr + "\",\n" +
                "   \"sports\":[\"fencing\", \"running\"],\n" +
                "   \"delicious\":[\"Yes\", \"No\", \"Maybe\"],\n" +
                "   \"my-large-text-attachment\":\"my-large-text-attachment-id\"\n" +
                "}";
        ExportSubtask subtask = makeSubtask(task, recordJsonText);

        // execute
        handler.handle(subtask);
        handler.uploadToSynapseForTask(task);

        // validate tsv file
        List<String> tsvLineList = TestUtil.bytesToLines(tsvBytes);
        assertEquals(tsvLineList.size(), 2);
        validateTsvHeaders(tsvLineList.get(0), "foo", "foooo", "unbounded-foo", "bar", "submitTime",
                "submitTime.timezone", "sports.fencing", "sports.football", "sports.running", "sports.swimming",
                "delicious.Yes", "delicious.No", "delicious.other", "my-large-text-attachment",
                HealthDataExportHandler.COLUMN_NAME_RAW_DATA);
        validateTsvRow(tsvLineList.get(1), "This is a string.", "Example (not) long string",
                "Potentially unbounded string", "42", String.valueOf(submitTimeMillis), "+0900", "true", "false",
                "true", "false", "true", "true", "Maybe", "This is my large text attachment", RAW_DATA_FILEHANDLE_ID);

        // validate metrics
        Multiset<String> counterMap = task.getMetrics().getCounterMap();
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".lineCount"), 1);
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".errorCount"), 0);

        // validate tsvInfo
        TsvInfo tsvInfo = handler.getTsvInfoForTask(task);
        assertEquals(tsvInfo.getLineCount(), 1);
        assertNotNull(tsvInfo.getRecordIds());
        List<String> recordIds = tsvInfo.getRecordIds();
        assertEquals(recordIds.size(), 1);
        for (String recordId : recordIds) {
            assertEquals(recordId, DUMMY_RECORD_ID);
        }

        verify(handler.getManager().getBridgeHelper()).updateRecordExporterStatus(any(), any());

        postValidation();
    }

    @Test
    public void schemalessExportHandlerTest() throws Exception {
        // Set up handler and test.
        SchemalessExportHandler handler = new SchemalessExportHandler();
        setup(handler);
        mockSynapseHelperUploadTsv(1);

        // Mock downloadLargeTextAttachment()
        when(mockSynapseHelper.downloadLargeTextAttachment("my-large-text-attachment-id")).thenReturn(
                "This is my large text attachment");

        // make subtasks
        String recordJsonText = "{\"dummy-key\":\"dummy-value\"}";
        ExportSubtask subtask = SynapseExportHandlerTest.makeSubtaskWithSchema(task, null, recordJsonText);

        // execute
        handler.handle(subtask);
        handler.uploadToSynapseForTask(task);

        // validate tsv file
        List<String> tsvLineList = TestUtil.bytesToLines(tsvBytes);
        assertEquals(tsvLineList.size(), 2);
        validateTsvHeaders(tsvLineList.get(0), HealthDataExportHandler.COLUMN_NAME_RAW_DATA);
        validateTsvRow(tsvLineList.get(1), RAW_DATA_FILEHANDLE_ID);

        // validate metrics
        Multiset<String> counterMap = task.getMetrics().getCounterMap();
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".lineCount"), 1);
        assertEquals(counterMap.count(handler.getDdbTableKeyValue() + ".errorCount"), 0);

        // validate tsvInfo
        TsvInfo tsvInfo = handler.getTsvInfoForTask(task);
        assertEquals(tsvInfo.getLineCount(), 1);
        assertNotNull(tsvInfo.getRecordIds());
        List<String> recordIds = tsvInfo.getRecordIds();
        assertEquals(recordIds.size(), 1);
        for (String recordId : recordIds) {
            assertEquals(recordId, DUMMY_RECORD_ID);
        }

        verify(handler.getManager().getBridgeHelper()).updateRecordExporterStatus(any(), any());

        postValidation();
    }

    private void mockSynapseHelperUploadTsv(int linesProcessed) throws Exception {
        when(mockSynapseHelper.uploadTsvFileToTable(eq(TEST_SYNAPSE_PROJECT_ID), eq(TEST_SYNAPSE_TABLE_ID),
                notNull(File.class))).thenAnswer(invocation -> {
            // on cleanup, the file is destroyed, so we need to intercept that file now
            File tsvFile = invocation.getArgumentAt(2, File.class);
            tsvBytes = mockFileHelper.getBytes(tsvFile);

            return linesProcessed;
        });
    }

    // We do this in a helper method instead of in an @AfterMethod, because @AfterMethod doesn't tell use the test
    // method if it fails.
    private void postValidation() {
        // tmpDir should be the only thing left in the fileHelper. Delete it, then verify isEmpty.
        mockFileHelper.deleteDir(task.getTmpDir());
        assertTrue(mockFileHelper.isEmpty());
    }

    public static ExportSubtask makeSubtask(ExportTask parentTask) throws IOException {
        return makeSubtask(parentTask, "{}");
    }

    public static ExportSubtask makeSubtask(ExportTask parentTask, String key, String value) throws IOException {
        return makeSubtask(parentTask, "{\"" + key + "\":\"" + value + "\"}");
    }

    public static ExportSubtask makeSubtask(ExportTask parentTask, String recordJsonText) throws IOException {
        return makeSubtaskWithSchema(parentTask, DUMMY_SCHEMA_KEY, recordJsonText);
    }

    public static ExportSubtask makeSubtaskWithSchema(ExportTask parentTask, UploadSchemaKey schemaKey,
            String recordJsonText) throws IOException {
        JsonNode recordJsonNode = DefaultObjectMapper.INSTANCE.readTree(recordJsonText);
        return new ExportSubtask.Builder().withOriginalRecord(makeDdbRecord()).withParentTask(parentTask)
                .withRecordData(recordJsonNode).withSchemaKey(schemaKey).withStudyId(TEST_STUDY_ID).build();
    }

    public static Item makeDdbRecord() {
        return new Item().withLong("createdOn", DUMMY_CREATED_ON).withString("healthCode", DUMMY_HEALTH_CODE)
                .withString("id", DUMMY_RECORD_ID).withString("metadata", DUMMY_METADATA_JSON_TEXT)
                .withStringSet("userDataGroups", DUMMY_DATA_GROUPS)
                .withString("userExternalId", "<p>unsanitized external id</p>")
                .withMap("userSubstudyMemberships", ImmutableMap.of("subA", "extA", "subB", ""))
                .withString("userSharingScope", DUMMY_USER_SHARING_SCOPE)
                .withString(HealthDataExportHandler.DDB_KEY_RAW_DATA_ATTACHMENT_ID, RAW_DATA_ATTACHMENT_ID);
    }

    public static void validateTsvHeaders(String line, String... extraColumnNameVarargs) {
        List<String> expectedColumnList = new ArrayList<>(COMMON_COLUMN_NAME_LIST);
        Collections.addAll(expectedColumnList, extraColumnNameVarargs);
        validateTsvRowHelper(line, expectedColumnList);
    }

    public static void validateTsvRow(String line, String... extraValueVarargs) {
        List<String> expectedColumnList = new ArrayList<>(COMMON_COLUMN_VALUE_LIST);
        Collections.addAll(expectedColumnList, extraValueVarargs);
        validateTsvRowHelper(line, expectedColumnList);
    }

    private static void validateTsvRowHelper(String line, List<String> expectedColumnList) {
        // Convert the columns to a TSV. This mimics the way CSVWriter escapes strings. Namely:
        // * It doesn't quote null values.
        // * It escapes " -> "" and \ -> \\
        // * It doesn't escape anything else.
        boolean firstColumn = true;
        StringBuilder expectedLineBuilder = new StringBuilder();
        for (String expectedColumn : expectedColumnList) {
            if (firstColumn) {
                firstColumn = false;
            } else {
                expectedLineBuilder.append('\t');
            }

            if (expectedColumn != null) {
                expectedLineBuilder.append('\"');
                expectedLineBuilder.append(expectedColumn.replace("\"", "\"\"").replace("\\",
                        "\\\\"));
                expectedLineBuilder.append('\"');
            }
        }

        assertEquals(line, expectedLineBuilder.toString());
    }

    public static List<ColumnModel> createTestSynapseColumnList(final List<ColumnDefinition> columnDefinitions) {
        ImmutableList.Builder<ColumnModel> columnListBuilder = ImmutableList.builder();

        ColumnModel recordIdColumn = new ColumnModel();
        recordIdColumn.setName("recordId");
        recordIdColumn.setColumnType(ColumnType.STRING);
        recordIdColumn.setMaximumSize(36L);
        columnListBuilder.add(recordIdColumn);

        ColumnModel appVersionColumn = new ColumnModel();
        appVersionColumn.setName("appVersion");
        appVersionColumn.setColumnType(ColumnType.STRING);
        appVersionColumn.setMaximumSize(48L);
        columnListBuilder.add(appVersionColumn);

        ColumnModel phoneInfoColumn = new ColumnModel();
        phoneInfoColumn.setName("phoneInfo");
        phoneInfoColumn.setColumnType(ColumnType.STRING);
        phoneInfoColumn.setMaximumSize(48L);
        columnListBuilder.add(phoneInfoColumn);

        ColumnModel uploadDateColumn = new ColumnModel();
        uploadDateColumn.setName("uploadDate");
        uploadDateColumn.setColumnType(ColumnType.STRING);
        uploadDateColumn.setMaximumSize(10L);
        columnListBuilder.add(uploadDateColumn);

        final List<ColumnModel> tempList = BridgeExporterUtil.convertToColumnList(columnDefinitions);
        columnListBuilder.addAll(tempList);

        return columnListBuilder.build();
    }

    public static List<ColumnDefinition> createTestSynapseColumnDefinitions() {
        // setup column definitions
        ImmutableList.Builder<ColumnDefinition> columnDefinitionsBuilder = ImmutableList.builder();

        ColumnDefinition healthCodeDefinition = new ColumnDefinition();
        healthCodeDefinition.setName("healthCode");
        healthCodeDefinition.setMaximumSize(36);
        healthCodeDefinition.setTransferMethod(TransferMethod.STRING);
        columnDefinitionsBuilder.add(healthCodeDefinition);

        ColumnDefinition externalIdDefinition = new ColumnDefinition();
        externalIdDefinition.setName("externalId");
        externalIdDefinition.setDdbName("userExternalId");
        externalIdDefinition.setMaximumSize(128);
        externalIdDefinition.setTransferMethod(TransferMethod.STRING);
        externalIdDefinition.setSanitize(true);
        columnDefinitionsBuilder.add(externalIdDefinition);

        ColumnDefinition dataGroupsDefinition = new ColumnDefinition();
        dataGroupsDefinition.setName("dataGroups");
        dataGroupsDefinition.setDdbName("userDataGroups");
        dataGroupsDefinition.setMaximumSize(100);
        dataGroupsDefinition.setTransferMethod(TransferMethod.STRINGSET);
        columnDefinitionsBuilder.add(dataGroupsDefinition);

        ColumnDefinition substudyMembershipsDefinition = new ColumnDefinition();
        substudyMembershipsDefinition.setName("substudyMemberships");
        substudyMembershipsDefinition.setDdbName("userSubstudyMemberships");
        substudyMembershipsDefinition.setMaximumSize(250);
        substudyMembershipsDefinition.setTransferMethod(TransferMethod.STRINGMAP);
        columnDefinitionsBuilder.add(substudyMembershipsDefinition);
        
        ColumnDefinition createdOnDefinition = new ColumnDefinition();
        createdOnDefinition.setName("createdOn");
        createdOnDefinition.setTransferMethod(TransferMethod.DATE);
        columnDefinitionsBuilder.add(createdOnDefinition);

        ColumnDefinition userSharingScopeDefinition = new ColumnDefinition();
        userSharingScopeDefinition.setName("userSharingScope");
        userSharingScopeDefinition.setMaximumSize(48);
        userSharingScopeDefinition.setTransferMethod(TransferMethod.STRING);
        columnDefinitionsBuilder.add(userSharingScopeDefinition);

        return columnDefinitionsBuilder.build();
    }

}

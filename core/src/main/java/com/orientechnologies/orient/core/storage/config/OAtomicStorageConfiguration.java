package com.orientechnologies.orient.core.storage.config;

import com.orientechnologies.common.concur.lock.OReadersWriterSpinLock;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OStringSerializer;
import com.orientechnologies.orient.core.config.OContextConfiguration;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.config.OStorageClusterConfiguration;
import com.orientechnologies.orient.core.config.OStorageConfiguration;
import com.orientechnologies.orient.core.config.OStorageConfigurationUpdateListener;
import com.orientechnologies.orient.core.config.OStorageEntryConfiguration;
import com.orientechnologies.orient.core.config.OStorageFileConfiguration;
import com.orientechnologies.orient.core.config.OStoragePaginatedClusterConfiguration;
import com.orientechnologies.orient.core.config.OStorageSegmentConfiguration;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OSerializationException;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.serialization.serializer.binary.impl.OLinkSerializer;
import com.orientechnologies.orient.core.storage.OPhysicalPosition;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.core.storage.cluster.OPaginatedCluster;
import com.orientechnologies.orient.core.storage.disk.OLocalPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperationsManager;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OPaginatedClusterFactory;
import com.orientechnologies.orient.core.storage.index.sbtree.local.OSBTree;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public final class OAtomicStorageConfiguration implements OStorageConfiguration {
  private static final String VERSION_PROPERTY                   = "version";
  private static final String SCHEMA_RECORD_ID_PROPERTY          = "schemaRecordId";
  private static final String INDEX_MANAGER_RECORD_ID_PROPERTY   = "indexManagerRecordId";
  private static final String LOCALE_LANGUAGE_PROPERTY           = "localeLanguage";
  private static final String LOCALE_COUNTRY_PROPERTY            = "localeCountry";
  private static final String DATE_FORMAT_PROPERTY               = "dateFormat";
  private static final String DATE_TIME_FORMAT_PROPERTY          = "dateTimeFormat";
  private static final String TIME_ZONE_PROPERTY                 = "timeZone";
  private static final String CHARSET_PROPERTY                   = "charset";
  private static final String CONFLICT_STRATEGY_PROPERTY         = "conflictStrategy";
  private static final String BINARY_FORMAT_VERSION_PROPERTY     = "binaryFormatVersion";
  private static final String CLUSTER_SELECTION_PROPERTY         = "clusterSelection";
  private static final String MINIMUM_CLUSTERS_PROPERTY          = "minimumClusters";
  private static final String RECORD_SERIALIZER_PROPERTY         = "recordSerializer";
  private static final String RECORD_SERIALIZER_VERSION_PROPERTY = "recordSerializerVersion";
  private static final String CONFIGURATION_PROPERTY             = "configuration";
  private static final String CREATED_AT_VERSION_PROPERTY        = "createAtVersion";
  private static final String PAGE_SIZE_PROPERTY                 = "pageSize";
  private static final String FREE_LIST_BOUNDARY_PROPERTY        = "freeListBoundary";
  private static final String MAX_KEY_SIZE_PROPERTY              = "maxKeySize";

  private static final String CLUSTERS_PREFIX_PROPERTY = "cluster_";
  private static final String PROPERTY_PREFIX_PROPERTY = "property_";
  private static final String ENGINE_PREFIX_PROPERTY   = "engine_";

  private OContextConfiguration configuration;
  private boolean               validation;
  private Locale                localeInstance;

  private final OSBTree<String, OIdentifiable> btree;
  private final OPaginatedCluster              cluster;

  private final OAbstractPaginatedStorage storage;
  private final OAtomicOperationsManager  atomicOperationsManager;
  private final OReadersWriterSpinLock    lock = new OReadersWriterSpinLock();

  private OStorageConfigurationUpdateListener updateListener;

  public OAtomicStorageConfiguration(OAbstractPaginatedStorage storage) {
    initConfiguration(new OContextConfiguration());
    cluster = OPaginatedClusterFactory.createCluster("config", OPaginatedCluster.getLatestBinaryVersion(), storage, ".cd", ".cm");
    btree = new OSBTree<>("config", ".bd", ".nd", storage);
    this.atomicOperationsManager = storage.getAtomicOperationsManager();

    this.storage = storage;
  }

  public void create() throws IOException {
    lock.acquireWriteLock();
    try {
      cluster.create(-1);
      btree.create(OStringSerializer.INSTANCE, OLinkSerializer.INSTANCE, null, 1, false, null);

      init();

      updateVersion();
    } finally {
      lock.releaseWriteLock();
    }
  }

  public void delete() throws IOException {
    lock.acquireWriteLock();
    try {
      cluster.delete();
      btree.delete();
    } finally {
      lock.releaseWriteLock();
    }
  }

  public void close() throws IOException {
    lock.acquireWriteLock();
    try {
      updateConfigurationProperty();
      updateMinimumClusters();

      cluster.close();
      btree.close();
    } finally {
      lock.releaseWriteLock();
    }
  }

  public OAtomicStorageConfiguration load(final OContextConfiguration configuration) throws OSerializationException, IOException {
    lock.acquireWriteLock();
    try {
      initConfiguration(configuration);

      cluster.open();
      btree.load("config", OStringSerializer.INSTANCE, OLinkSerializer.INSTANCE, null, 1, false, null);

      readConfiguration();
      readMinimumClusters();
    } finally {
      lock.releaseWriteLock();
    }

    return this;
  }

  public void setMinimumClusters(final int minimumClusters) {
    lock.acquireWriteLock();
    try {
      getContextConfiguration().setValue(OGlobalConfiguration.CLASS_MINIMUM_CLUSTERS, minimumClusters);
      autoInitClusters();
    } finally {
      lock.releaseWriteLock();
    }
  }

  private void updateMinimumClusters() {
    updateIntProperty(MINIMUM_CLUSTERS_PROPERTY, getMinimumClusters());
  }

  private void readMinimumClusters() {
    setMinimumClusters(readIntProperty(MINIMUM_CLUSTERS_PROPERTY));
  }

  public int getMinimumClusters() {
    lock.acquireReadLock();
    try {
      final int mc = getContextConfiguration().getValueAsInteger(OGlobalConfiguration.CLASS_MINIMUM_CLUSTERS);
      if (mc == 0) {
        autoInitClusters();
        return (Integer) getContextConfiguration().getValue(OGlobalConfiguration.CLASS_MINIMUM_CLUSTERS);
      }
      return mc;
    } finally {
      lock.releaseReadLock();
    }
  }

  public void initConfiguration(OContextConfiguration conf) {
    lock.acquireWriteLock();
    try {
      this.configuration = conf;
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public OContextConfiguration getContextConfiguration() {
    lock.acquireReadLock();
    try {
      return configuration;
    } finally {
      lock.releaseReadLock();
    }
  }

  public byte[] toStream(Charset charset) throws OSerializationException {
    return toStream(Integer.MAX_VALUE, charset);
  }

  /**
   * Added version used for managed Network Versioning.
   */
  public byte[] toStream(final int iNetworkVersion, Charset charset) throws OSerializationException {
    lock.acquireReadLock();
    try {
      final StringBuilder buffer = new StringBuilder(8192);

      write(buffer, CURRENT_VERSION);
      write(buffer, null);

      write(buffer, getSchemaRecordId());
      write(buffer, "");
      write(buffer, getIndexMgrRecordId());

      write(buffer, getLocaleLanguage());
      write(buffer, getLocaleCountry());
      write(buffer, getDateFormat());
      write(buffer, getDateFormat());

      write(buffer, getTimeZone().getID());
      write(buffer, charset);
      if (iNetworkVersion > 24) {
        write(buffer, getConflictStrategy());
      }

      phySegmentToStream(buffer, new OStorageSegmentConfiguration());

      final List<OStorageClusterConfiguration> clusters = getClusters();
      write(buffer, clusters.size());
      for (OStorageClusterConfiguration c : clusters) {
        if (c == null) {
          write(buffer, -1);
          continue;
        }

        write(buffer, c.getId());
        write(buffer, c.getName());
        write(buffer, c.getDataSegmentId());

        if (c instanceof OStoragePaginatedClusterConfiguration) {
          write(buffer, "d");

          final OStoragePaginatedClusterConfiguration paginatedClusterConfiguration = (OStoragePaginatedClusterConfiguration) c;

          write(buffer, paginatedClusterConfiguration.useWal);
          write(buffer, paginatedClusterConfiguration.recordOverflowGrowFactor);
          write(buffer, paginatedClusterConfiguration.recordGrowFactor);
          write(buffer, paginatedClusterConfiguration.compression);

          if (iNetworkVersion >= 31) {
            write(buffer, paginatedClusterConfiguration.encryption);
          }
          if (iNetworkVersion > 24) {
            write(buffer, paginatedClusterConfiguration.conflictStrategy);
          }
          if (iNetworkVersion > 25) {
            write(buffer, paginatedClusterConfiguration.getStatus().name());
          }

          if (iNetworkVersion >= Integer.MAX_VALUE) {
            write(buffer, paginatedClusterConfiguration.getBinaryVersion());
          }
        }
      }
      if (iNetworkVersion <= 25) {
        // dataSegment array
        write(buffer, 0);
        // tx Segment File
        write(buffer, "");
        write(buffer, "");
        write(buffer, 0);
        // tx segment flags
        write(buffer, false);
        write(buffer, false);
      }

      final List<OStorageEntryConfiguration> properties = getProperties();
      write(buffer, properties.size());
      for (OStorageEntryConfiguration e : properties) {
        entryToStream(buffer, e);
      }

      write(buffer, getBinaryFormatVersion());
      write(buffer, getClusterSelection());
      write(buffer, getMinimumClusters());

      if (iNetworkVersion > 24) {
        write(buffer, getRecordSerializer());
        write(buffer, getRecordSerializerVersion());

        // WRITE CONFIGURATION
        write(buffer, configuration.getContextSize());
        for (String k : configuration.getContextKeys()) {
          final OGlobalConfiguration cfg = OGlobalConfiguration.findByKey(k);
          write(buffer, k);
          if (cfg != null) {
            write(buffer, cfg.isHidden() ? null : configuration.getValueAsString(cfg));
          } else {
            write(buffer, null);
            OLogManager.instance().warn(this, "Storing configuration for property:'" + k + "' not existing in current version");
          }
        }
      }

      final List<IndexEngineData> engines = loadIndexEngines();
      write(buffer, engines.size());
      for (IndexEngineData engineData : engines) {
        write(buffer, engineData.getName());
        write(buffer, engineData.getAlgorithm());
        write(buffer, engineData.getIndexType() == null ? "" : engineData.getIndexType());

        write(buffer, engineData.getValueSerializerId());
        write(buffer, engineData.getKeySerializedId());

        write(buffer, engineData.isAutomatic());
        write(buffer, engineData.getDurableInNonTxMode());

        write(buffer, engineData.getVersion());
        write(buffer, engineData.isNullValuesSupport());
        write(buffer, engineData.getKeySize());
        write(buffer, engineData.getEncryption());
        write(buffer, engineData.getEncryptionOptions());

        if (engineData.getKeyTypes() != null) {
          write(buffer, engineData.getKeyTypes().length);
          for (OType type : engineData.getKeyTypes()) {
            write(buffer, type.name());
          }
        } else {
          write(buffer, 0);
        }

        if (engineData.getEngineProperties() == null) {
          write(buffer, 0);
        } else {
          write(buffer, engineData.getEngineProperties().size());
          for (Map.Entry<String, String> property : engineData.getEngineProperties().entrySet()) {
            write(buffer, property.getKey());
            write(buffer, property.getValue());
          }
        }
      }

      write(buffer, getCreatedAtVersion());
      write(buffer, getPageSize());
      write(buffer, getFreeListBoundary());
      write(buffer, getMaxKeySize());

      // PLAIN: ALLOCATE ENOUGH SPACE TO REUSE IT EVERY TIME
      buffer.append("|");

      return buffer.toString().getBytes(charset);
    } finally {
      lock.releaseReadLock();
    }
  }

  private static void entryToStream(final StringBuilder buffer, final OStorageEntryConfiguration entry) {
    write(buffer, entry.name);
    write(buffer, entry.value);
  }

  private static void phySegmentToStream(final StringBuilder buffer, final OStorageSegmentConfiguration segment) {
    write(buffer, segment.getLocation());
    write(buffer, segment.maxSize);
    write(buffer, segment.fileType);
    write(buffer, segment.fileStartSize);
    write(buffer, segment.fileMaxSize);
    write(buffer, segment.fileIncrementSize);
    write(buffer, segment.defrag);

    write(buffer, segment.infoFiles.length);
    for (OStorageFileConfiguration f : segment.infoFiles) {
      fileToStream(buffer, f);
    }
  }

  private static void fileToStream(final StringBuilder iBuffer, final OStorageFileConfiguration iFile) {
    write(iBuffer, iFile.path);
    write(iBuffer, iFile.type);
    write(iBuffer, iFile.maxSize);
  }

  private static void write(final StringBuilder buffer, final Object value) {
    if (buffer.length() > 0) {
      buffer.append('|');
    }

    buffer.append(value != null ? value.toString() : ' ');
  }

  private void updateVersion() {
    updateIntProperty(VERSION_PROPERTY, CURRENT_VERSION);
  }

  @Override
  public int getVersion() {
    lock.acquireReadLock();
    try {
      return readIntProperty(VERSION_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }

  }

  @Override
  public String getName() {
    return null;
  }

  public void setSchemaRecordId(String schemaRecordId) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(SCHEMA_RECORD_ID_PROPERTY, schemaRecordId);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getSchemaRecordId() {
    lock.acquireReadLock();
    try {
      return readStringProperty(SCHEMA_RECORD_ID_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setIndexMgrRecordId(String indexMgrRecordId) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(INDEX_MANAGER_RECORD_ID_PROPERTY, indexMgrRecordId);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getIndexMgrRecordId() {
    lock.acquireReadLock();
    try {
      return readStringProperty(INDEX_MANAGER_RECORD_ID_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setLocaleLanguage(String value) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(LOCALE_LANGUAGE_PROPERTY, value);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getLocaleLanguage() {
    lock.acquireReadLock();
    try {
      return readStringProperty(LOCALE_LANGUAGE_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setLocaleCountry(String value) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(LOCALE_COUNTRY_PROPERTY, value);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getLocaleCountry() {
    lock.acquireReadLock();
    try {
      return readStringProperty(LOCALE_COUNTRY_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setDateFormat(String dateFormat) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(DATE_FORMAT_PROPERTY, dateFormat);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getDateFormat() {
    lock.acquireReadLock();
    try {
      final String dateFormat = readStringProperty(DATE_FORMAT_PROPERTY);
      assert dateFormat != null;

      return dateFormat;
    } finally {
      lock.releaseReadLock();
    }
  }

  @Override
  public SimpleDateFormat getDateFormatInstance() {
    lock.acquireReadLock();
    try {

      final SimpleDateFormat dateFormatInstance = new SimpleDateFormat(getDateFormat());
      dateFormatInstance.setLenient(false);
      dateFormatInstance.setTimeZone(getTimeZone());

      return dateFormatInstance;
    } finally {
      lock.releaseReadLock();
    }
  }

  @Override
  public String getDateTimeFormat() {
    lock.acquireReadLock();
    try {
      final String dateTimeFormat = readStringProperty(DATE_TIME_FORMAT_PROPERTY);
      assert dateTimeFormat != null;

      return dateTimeFormat;
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setDateTimeFormat(String dateTimeFormat) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(DATE_TIME_FORMAT_PROPERTY, dateTimeFormat);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public SimpleDateFormat getDateTimeFormatInstance() {
    lock.acquireReadLock();
    try {
      final SimpleDateFormat dateTimeFormatInstance = new SimpleDateFormat(getDateTimeFormat());
      dateTimeFormatInstance.setLenient(false);
      dateTimeFormatInstance.setTimeZone(getTimeZone());
      return dateTimeFormatInstance;
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setTimeZone(TimeZone timeZone) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(TIME_ZONE_PROPERTY, timeZone.getID());
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public TimeZone getTimeZone() {
    lock.acquireReadLock();
    try {
      final String timeZone = readStringProperty(TIME_ZONE_PROPERTY);
      return TimeZone.getTimeZone(timeZone);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setCharset(String charset) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(CHARSET_PROPERTY, charset);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getCharset() {
    lock.acquireReadLock();
    try {
      return readStringProperty(CHARSET_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setConflictStrategy(String conflictStrategy) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(CONFLICT_STRATEGY_PROPERTY, conflictStrategy);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getConflictStrategy() {
    lock.acquireReadLock();
    try {
      return readStringProperty(CONFLICT_STRATEGY_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  private void updateBinaryFormatVersion() {
    updateIntProperty(BINARY_FORMAT_VERSION_PROPERTY, CURRENT_BINARY_FORMAT_VERSION);
  }

  @Override
  public int getBinaryFormatVersion() {
    lock.acquireReadLock();
    try {
      return readIntProperty(BINARY_FORMAT_VERSION_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setClusterSelection(String clusterSelection) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(CLUSTER_SELECTION_PROPERTY, clusterSelection);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getClusterSelection() {
    lock.acquireReadLock();
    try {
      return readStringProperty(CLUSTER_SELECTION_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setRecordSerializer(String recordSerializer) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(RECORD_SERIALIZER_PROPERTY, recordSerializer);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getRecordSerializer() {
    lock.acquireReadLock();
    try {
      return readStringProperty(RECORD_SERIALIZER_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setRecordSerializerVersion(int recordSerializerVersion) {
    lock.acquireWriteLock();
    try {
      updateIntProperty(RECORD_SERIALIZER_VERSION_PROPERTY, recordSerializerVersion);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public int getRecordSerializerVersion() {
    lock.acquireReadLock();
    try {
      return readIntProperty(RECORD_SERIALIZER_VERSION_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  private void updateConfigurationProperty() {
    final List<byte[]> entries = new ArrayList<>();
    int totalSize = 0;

    final byte[] contextSize = new byte[OIntegerSerializer.INT_SIZE];
    totalSize += contextSize.length;
    entries.add(contextSize);

    OIntegerSerializer.INSTANCE.serializeNative(configuration.getContextSize(), contextSize, 0);

    for (String k : configuration.getContextKeys()) {
      final OGlobalConfiguration cfg = OGlobalConfiguration.findByKey(k);
      final byte[] key = serializeStringValue(k);
      totalSize += key.length;
      entries.add(key);

      if (cfg != null) {
        final byte[] value = serializeStringValue(cfg.isHidden() ? null : configuration.getValueAsString(cfg));
        totalSize += value.length;
        entries.add(value);
      } else {
        final byte[] value = serializeStringValue(null);
        totalSize += value.length;
        entries.add(value);

        OLogManager.instance().warn(this, "Storing configuration for property:'" + k + "' not existing in current version");
      }
    }

    final byte[] property = mergeBinaryEntries(totalSize, entries);
    storeProperty(CONFIGURATION_PROPERTY, property);
  }

  private void readConfiguration() {
    final byte[] property = readProperty(CONFIGURATION_PROPERTY);

    if (property == null) {
      return;
    }

    int pos = 0;
    final int size = OIntegerSerializer.INSTANCE.deserializeNative(property, pos);
    pos += OIntegerSerializer.INT_SIZE;

    for (int i = 0; i < size; i++) {
      final String key = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      final String value = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      final OGlobalConfiguration cfg = OGlobalConfiguration.findByKey(key);
      if (cfg != null) {
        if (value != null) {
          configuration.setValue(key, OType.convert(value, cfg.getType()));
        }
      } else {
        OLogManager.instance().warn(this, "Ignored storage configuration because not supported: %s=%s", key, value);
      }
    }
  }

  public void setCreationVersion(String version) {
    lock.acquireWriteLock();
    try {
      updateStringProperty(CREATED_AT_VERSION_PROPERTY, version);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public String getCreatedAtVersion() {
    lock.acquireReadLock();
    try {
      return readStringProperty(CREATED_AT_VERSION_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setPageSize(int pageSize) {
    lock.acquireWriteLock();
    try {
      updateIntProperty(PAGE_SIZE_PROPERTY, pageSize);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public int getPageSize() {
    lock.acquireReadLock();
    try {
      return readIntProperty(PAGE_SIZE_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setFreeListBoundary(int freeListBoundary) {
    lock.acquireWriteLock();
    try {
      updateIntProperty(FREE_LIST_BOUNDARY_PROPERTY, freeListBoundary);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public int getFreeListBoundary() {
    lock.acquireReadLock();
    try {
      return readIntProperty(FREE_LIST_BOUNDARY_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setMaxKeySize(int maxKeySize) {
    lock.acquireWriteLock();
    try {
      updateIntProperty(MAX_KEY_SIZE_PROPERTY, maxKeySize);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public int getMaxKeySize() {
    lock.acquireReadLock();
    try {
      return readIntProperty(MAX_KEY_SIZE_PROPERTY);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void setProperty(String name, String value) {
    lock.acquireWriteLock();
    try {
      if ("validation".equalsIgnoreCase(name)) {
        validation = "true".equalsIgnoreCase(value);
      }

      final String key = PROPERTY_PREFIX_PROPERTY + name;
      updateStringProperty(key, value);
    } finally {
      lock.releaseWriteLock();
    }
  }

  public void setValidation(final boolean validation) {
    setProperty("validation", validation ? "true" : "false");
  }

  @Override
  public boolean isValidationEnabled() {
    lock.acquireReadLock();
    try {
      return validation;
    } finally {
      lock.releaseReadLock();
    }
  }

  @Override
  public String getDirectory() {
    if (storage instanceof OLocalPaginatedStorage) {
      return ((OLocalPaginatedStorage) storage).getStoragePath().toString();
    } else {
      return null;
    }
  }

  @Override
  public String getProperty(String name) {
    lock.acquireReadLock();
    try {
      return readStringProperty(PROPERTY_PREFIX_PROPERTY + name);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public List<OStorageEntryConfiguration> getProperties() {
    lock.acquireReadLock();
    try {
      final OSBTree.OSBTreeCursor<String, OIdentifiable> cursor = btree.iterateEntriesMajor(PROPERTY_PREFIX_PROPERTY, false, true);
      final List<OStorageEntryConfiguration> result = new ArrayList<>();
      Map.Entry<String, OIdentifiable> entry = cursor.next(-1);
      while (entry != null) {
        if (!entry.getKey().startsWith(PROPERTY_PREFIX_PROPERTY)) {
          break;
        }

        final ORawBuffer buffer = cluster.readRecord(entry.getValue().getIdentity().getClusterPosition(), false);
        result.add(new OStorageEntryConfiguration(entry.getKey().substring(PROPERTY_PREFIX_PROPERTY.length()),
            deserializeStringValue(buffer.buffer, 0)));

        entry = cursor.next(-1);
      }

      return result;
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Can not fetch list of configuration properties"), e);
    } finally {
      lock.releaseReadLock();
    }
  }

  public Locale getLocaleInstance() {
    lock.acquireReadLock();
    try {
      if (localeInstance == null) {
        try {
          localeInstance = new Locale(getLocaleLanguage(), getLocaleCountry());
        } catch (RuntimeException e) {
          localeInstance = Locale.getDefault();
          OLogManager.instance()
              .errorNoDb(this, "Error during initialization of locale, default one %s will be used", e, localeInstance);

        }
      }

      return localeInstance;
    } finally {
      lock.releaseReadLock();
    }
  }

  @Override
  public boolean isStrictSql() {
    return true;
  }

  public void clearProperties() {
    lock.acquireWriteLock();
    try {
      final OSBTree.OSBTreeCursor<String, OIdentifiable> cursor = btree.iterateEntriesMajor(PROPERTY_PREFIX_PROPERTY, false, true);

      final List<String> keysToRemove = new ArrayList<>();
      final List<ORID> ridsToRemove = new ArrayList<>();

      Map.Entry<String, OIdentifiable> entry = cursor.next(-1);
      while (entry != null) {
        if (!entry.getKey().startsWith(PROPERTY_PREFIX_PROPERTY)) {
          break;
        }

        keysToRemove.add(entry.getKey());
        ridsToRemove.add(entry.getValue().getIdentity());

        entry = cursor.next(-1);
      }

      boolean rollback = false;
      atomicOperationsManager.startAtomicOperation("dbConfig", true);
      try {
        for (String key : keysToRemove) {
          btree.remove(key);
        }

        for (ORID rid : ridsToRemove) {
          cluster.deleteRecord(rid.getClusterPosition());
        }
      } catch (Exception e) {
        rollback = true;
        throw e;
      } finally {
        atomicOperationsManager.endAtomicOperation(rollback);
      }
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Error during clear of configuration properties"), e);
    } finally {
      lock.releaseWriteLock();
    }
  }

  public void removeProperty(String name) {
    lock.acquireWriteLock();
    try {
      dropProperty(PROPERTY_PREFIX_PROPERTY + name);
    } finally {
      lock.releaseWriteLock();
    }
  }

  public void addIndexEngine(String name, IndexEngineData engineData) {
    lock.acquireWriteLock();
    try {
      final OIdentifiable identifiable = btree.get(ENGINE_PREFIX_PROPERTY + name);
      if (identifiable != null) {
        OLogManager.instance()
            .warn(this, "Index engine with name '" + engineData.getName() + "' already contained in database configuration");
      } else {
        storeProperty(ENGINE_PREFIX_PROPERTY + name, serializeIndexEngineProperty(engineData));
      }
    } finally {
      lock.releaseWriteLock();
    }
  }

  public void deleteIndexEngine(String name) {
    lock.acquireWriteLock();
    try {
      dropProperty(ENGINE_PREFIX_PROPERTY + name);
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public Set<String> indexEngines() {
    lock.acquireReadLock();
    try {
      final OSBTree.OSBTreeCursor<String, OIdentifiable> cursor = btree.iterateEntriesMajor(ENGINE_PREFIX_PROPERTY, false, true);

      final Set<String> result = new HashSet<>();
      Map.Entry<String, OIdentifiable> entry = cursor.next(-1);
      while (entry != null) {
        if (!entry.getKey().startsWith(ENGINE_PREFIX_PROPERTY)) {
          break;
        }

        result.add(entry.getKey().substring(ENGINE_PREFIX_PROPERTY.length()));

        entry = cursor.next(-1);
      }

      return result;
    } finally {
      lock.releaseReadLock();
    }
  }

  private List<IndexEngineData> loadIndexEngines() {
    try {
      final OSBTree.OSBTreeCursor<String, OIdentifiable> cursor = btree.iterateEntriesMajor(ENGINE_PREFIX_PROPERTY, false, true);
      final List<IndexEngineData> result = new ArrayList<>();

      Map.Entry<String, OIdentifiable> entry = cursor.next(-1);
      while (entry != null) {
        if (!entry.getKey().startsWith(ENGINE_PREFIX_PROPERTY)) {
          break;
        }

        final String name = entry.getKey().substring(ENGINE_PREFIX_PROPERTY.length());
        final ORawBuffer buffer = cluster.readRecord(entry.getValue().getIdentity().getClusterPosition(), false);
        final IndexEngineData engine = deserializeIndexEngineProperty(name, buffer.buffer);
        result.add(engine);

        entry = cursor.next(-1);
      }

      return result;
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Can not fetch list of index ingines"), e);
    }
  }

  @Override
  public IndexEngineData getIndexEngine(String name) {
    lock.acquireReadLock();
    try {
      final byte[] property = readProperty(ENGINE_PREFIX_PROPERTY + name);
      if (property == null) {
        return null;
      }

      return deserializeIndexEngineProperty(name, property);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void updateCluster(OStorageClusterConfiguration config) {
    lock.acquireWriteLock();
    try {
      storeProperty(CLUSTERS_PREFIX_PROPERTY + config.getId(), updateClusterConfig(config));
    } finally {
      lock.releaseWriteLock();
    }
  }

  public void setClusterStatus(final int clusterId, final OStorageClusterConfiguration.STATUS iStatus) {
    lock.acquireWriteLock();
    try {
      final byte[] property = readProperty(CLUSTERS_PREFIX_PROPERTY + clusterId);
      if (property != null) {
        final OStorageClusterConfiguration clusterCfg = deserializeStorageClusterConfig(clusterId, property);
        clusterCfg.setStatus(iStatus);
        updateCluster(clusterCfg);
      }
    } finally {
      lock.releaseWriteLock();
    }
  }

  @Override
  public List<OStorageClusterConfiguration> getClusters() {
    lock.acquireReadLock();
    try {
      final List<OStorageClusterConfiguration> clusters = new ArrayList<>();
      OSBTree.OSBTreeCursor<String, OIdentifiable> cursor = btree.iterateEntriesMajor(CLUSTERS_PREFIX_PROPERTY, false, true);

      Map.Entry<String, OIdentifiable> entry = cursor.next(-1);
      while (entry != null) {
        if (!entry.getKey().startsWith(CLUSTERS_PREFIX_PROPERTY)) {
          break;
        }

        final int id = Integer.parseInt(entry.getKey().substring(CLUSTERS_PREFIX_PROPERTY.length()));
        final ORawBuffer buffer = cluster.readRecord(entry.getValue().getIdentity().getClusterPosition(), false);

        if (clusters.size() <= id) {
          final int diff = id - clusters.size();

          for (int i = 0; i < diff; i++) {
            clusters.add(null);
          }

          clusters.add(deserializeStorageClusterConfig(id, buffer.buffer));
          assert id == clusters.size() - 1;
        } else {
          clusters.set(id, deserializeStorageClusterConfig(id, buffer.buffer));
        }

        entry = cursor.next(-1);
      }

      return clusters;
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Error during reading list of clusters"), e);
    } finally {
      lock.releaseReadLock();
    }
  }

  public void dropCluster(int clusterId) {
    lock.acquireWriteLock();
    try {
      dropProperty(CLUSTERS_PREFIX_PROPERTY + clusterId);
    } finally {
      lock.releaseWriteLock();
    }
  }

  public void setConfigurationUpdateListener(OStorageConfigurationUpdateListener updateListener) {
    lock.acquireWriteLock();
    try {
      this.updateListener = updateListener;
    } finally {
      lock.releaseWriteLock();
    }
  }

  private static byte[] serializeIndexEngineProperty(IndexEngineData indexEngineData) {
    int totalSize = 0;
    final List<byte[]> entries = new ArrayList<>();

    final byte[] numericProperties = new byte[2 * OIntegerSerializer.INT_SIZE + 4 * OByteSerializer.BYTE_SIZE];
    totalSize += numericProperties.length;
    entries.add(numericProperties);

    {
      int pos = 0;
      OIntegerSerializer.INSTANCE.serializeNative(indexEngineData.getVersion(), numericProperties, pos);
      pos += OIntegerSerializer.INT_SIZE;

      numericProperties[pos] = indexEngineData.getValueSerializerId();
      pos++;
      numericProperties[pos] = indexEngineData.getKeySerializedId();
      pos++;
      numericProperties[pos] = indexEngineData.isAutomatic() ? (byte) 1 : 0;
      pos++;
      numericProperties[pos] = indexEngineData.isNullValuesSupport() ? (byte) 1 : 0;
      pos++;

      OIntegerSerializer.INSTANCE.serializeNative(indexEngineData.getKeySize(), numericProperties, pos);
    }

    final byte[] algorithm = serializeStringValue(indexEngineData.getAlgorithm());
    totalSize += algorithm.length;
    entries.add(algorithm);

    final byte[] indexType = serializeStringValue(indexEngineData.getIndexType() == null ? "" : indexEngineData.getIndexType());
    entries.add(indexType);
    totalSize += indexType.length;

    final byte[] encryption = serializeStringValue(indexEngineData.getEncryption());
    totalSize += encryption.length;
    entries.add(encryption);

    final OType[] keyTypesValue = indexEngineData.getKeyTypes();
    final byte[] keyTypesSize = new byte[4];
    OIntegerSerializer.INSTANCE.serializeNative(keyTypesValue.length, keyTypesSize, 0);
    totalSize += keyTypesSize.length;
    entries.add(keyTypesSize);

    for (OType typeValue : keyTypesValue) {
      final byte[] keyTypeName = serializeStringValue(typeValue.name());
      totalSize += keyTypeName.length;
      entries.add(keyTypeName);
    }

    final Map<String, String> engineProperties = indexEngineData.getEngineProperties();
    final byte[] enginePropertiesSize = new byte[OIntegerSerializer.INT_SIZE];
    totalSize += enginePropertiesSize.length;
    entries.add(enginePropertiesSize);

    if (engineProperties != null) {
      OIntegerSerializer.INSTANCE.serializeNative(engineProperties.size(), enginePropertiesSize, 0);

      for (Map.Entry<String, String> engineProperty : engineProperties.entrySet()) {
        final byte[] key = serializeStringValue(engineProperty.getKey());
        totalSize += key.length;
        entries.add(key);

        final byte[] value = serializeStringValue(engineProperty.getValue());
        totalSize += value.length;
        entries.add(value);
      }
    }

    return mergeBinaryEntries(totalSize, entries);
  }

  private IndexEngineData deserializeIndexEngineProperty(String name, byte[] property) {
    int pos = 0;

    final int version = OIntegerSerializer.INSTANCE.deserializeNative(property, pos);
    pos += OIntegerSerializer.INT_SIZE;

    final byte valueSerializerId = property[pos];
    pos++;

    final byte keySerializerId = property[pos];
    pos++;

    final boolean isAutomatic = property[pos] == 1;
    pos++;

    final boolean isNullValueSupport = property[pos] == 1;
    pos++;

    final int keySize = OIntegerSerializer.INSTANCE.deserializeNative(property, pos);
    pos += OIntegerSerializer.INT_SIZE;

    final String algorithm = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final String indexType = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final String encryption = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final int keyTypesSize = OIntegerSerializer.INSTANCE.deserializeNative(property, pos);
    pos += OIntegerSerializer.INT_SIZE;

    final OType[] keyTypes = new OType[keyTypesSize];
    for (int i = 0; i < keyTypesSize; i++) {
      final String typeName = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      keyTypes[i] = OType.valueOf(typeName);
    }

    final Map<String, String> engineProperties = new HashMap<>();
    final int enginePropertiesSize = OIntegerSerializer.INSTANCE.deserializeNative(property, pos);
    pos += OIntegerSerializer.INT_SIZE;

    for (int i = 0; i < enginePropertiesSize; i++) {
      final String key = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      final String value = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      engineProperties.put(key, value);
    }

    return new IndexEngineData(name, algorithm, indexType, true, version, valueSerializerId, keySerializerId, isAutomatic, keyTypes,
        isNullValueSupport, keySize, encryption, configuration.getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_KEY),
        engineProperties);
  }

  private static byte[] mergeBinaryEntries(int totalSize, List<byte[]> entries) {
    final byte[] property = new byte[totalSize];
    int pos = 0;
    for (byte[] entry : entries) {
      System.arraycopy(entry, 0, property, pos, entry.length);
      pos += entry.length;
    }

    assert pos == property.length;
    return property;
  }

  private static byte[] updateClusterConfig(OStorageClusterConfiguration cluster) {
    int totalSize = 0;
    final List<byte[]> entries = new ArrayList<>();

    final byte[] name = serializeStringValue(cluster.getName());
    totalSize += name.length;
    entries.add(name);

    final OStoragePaginatedClusterConfiguration paginatedClusterConfiguration = (OStoragePaginatedClusterConfiguration) cluster;
    final byte[] numericData = new byte[OIntegerSerializer.INT_SIZE + OByteSerializer.BYTE_SIZE];
    totalSize += numericData.length;
    entries.add(numericData);

    numericData[0] = paginatedClusterConfiguration.useWal ? (byte) 1 : 0;

    OIntegerSerializer.INSTANCE.serializeNative(paginatedClusterConfiguration.getBinaryVersion(), numericData, 1);

    final byte[] encryption = serializeStringValue(paginatedClusterConfiguration.encryption);
    totalSize += encryption.length;
    entries.add(encryption);

    final byte[] conflictStrategy = serializeStringValue(paginatedClusterConfiguration.conflictStrategy);
    totalSize += conflictStrategy.length;
    entries.add(conflictStrategy);

    final byte[] status = serializeStringValue(paginatedClusterConfiguration.getStatus().name());
    totalSize += status.length;
    entries.add(status);

    final byte[] compression = serializeStringValue(paginatedClusterConfiguration.compression);
    entries.add(compression);
    totalSize += compression.length;

    return mergeBinaryEntries(totalSize, entries);
  }

  private OStorageClusterConfiguration deserializeStorageClusterConfig(int id, byte[] property) {
    int pos = 0;

    final String name = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final boolean useWal = (property[pos] == 1);
    pos++;

    final int binaryVersion = OIntegerSerializer.INSTANCE.deserializeNative(property, pos);
    pos += OIntegerSerializer.INT_SIZE;

    final String encryption = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final String conflictStrategy = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final String status = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final String compression = deserializeStringValue(property, pos);

    return new OStoragePaginatedClusterConfiguration(this, id, name, null, useWal, 0, 0, compression, encryption,
        configuration.getValueAsString(OGlobalConfiguration.STORAGE_ENCRYPTION_KEY), conflictStrategy,
        OStorageClusterConfiguration.STATUS.valueOf(status), binaryVersion);
  }

  private void dropProperty(String name) {
    try {
      boolean rollback = false;
      atomicOperationsManager.startAtomicOperation("dbConfig", true);
      try {
        final OIdentifiable identifiable = btree.remove(name);
        if (identifiable != null) {
          cluster.deleteRecord(identifiable.getIdentity().getClusterPosition());
        }
      } catch (Exception e) {
        rollback = true;
        throw e;
      } finally {
        atomicOperationsManager.endAtomicOperation(rollback);
      }
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Error during drop of property " + name), e);
    }
  }

  private void updateStringProperty(String name, String value) {
    final byte[] property = serializeStringValue(value);

    storeProperty(name, property);
  }

  private static byte[] serializeStringValue(String value) {
    final byte[] property;
    if (value == null) {
      property = new byte[1];
    } else {
      final byte[] rawString = value.getBytes(StandardCharsets.UTF_16);
      property = new byte[rawString.length + 1 + OIntegerSerializer.INT_SIZE];
      property[0] = 1;

      OIntegerSerializer.INSTANCE.serializeNative(rawString.length, property, 1);

      System.arraycopy(rawString, 0, property, 5, rawString.length);
    }

    return property;
  }

  private static String deserializeStringValue(byte[] raw, int start) {
    if (raw[start] == 0) {
      return null;
    }

    final int stringSize = OIntegerSerializer.INSTANCE.deserializeNative(raw, start + 1);
    return new String(raw, start + 5, stringSize, StandardCharsets.UTF_16);
  }

  private static int getSerializedStringSize(byte[] raw, int start) {
    if (raw[start] == 0) {
      return 1;
    }

    return OIntegerSerializer.INSTANCE.deserializeNative(raw, start + 1) + 5;
  }

  private void updateIntProperty(String name, int value) {
    final byte[] property = new byte[OIntegerSerializer.INT_SIZE];
    OIntegerSerializer.INSTANCE.serializeNative(value, property, 0);

    storeProperty(name, property);
  }

  private void storeProperty(String name, byte[] property) {
    try {
      boolean rollback = false;
      atomicOperationsManager.startAtomicOperation("dbConfig", true);
      try {
        OIdentifiable identity = btree.get(name);
        if (identity == null) {
          final OPhysicalPosition position = cluster.createRecord(property, 0, (byte) 0, null);
          identity = new ORecordId(0, position.clusterPosition);
          btree.put(name, identity);
        } else {
          cluster.updateRecord(identity.getIdentity().getClusterPosition(), property, -1, (byte) 0);
        }

        if (updateListener != null) {
          updateListener.onUpdate(this);
        }
      } catch (Exception e) {
        rollback = true;
        throw e;
      } finally {
        atomicOperationsManager.endAtomicOperation(rollback);
      }
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Error during update of configuration property " + name), e);
    }
  }

  private byte[] readProperty(String name) {
    try {
      final OIdentifiable identifable = btree.get(name);
      if (identifable == null) {
        return null;
      }

      final ORawBuffer buffer = cluster.readRecord(identifable.getIdentity().getClusterPosition(), false);
      return buffer.buffer;
    } catch (IOException e) {
      throw OException.wrapException(new OStorageException("Error during read of configuration property " + name), e);
    }
  }

  private String readStringProperty(String name) {
    final byte[] property = readProperty(name);
    if (property == null) {
      return null;
    }

    return deserializeStringValue(property, 0);
  }

  private int readIntProperty(String name) {
    final byte[] property = readProperty(name);
    return OIntegerSerializer.INSTANCE.deserializeNative(property, 0);
  }

  private void init() {
    updateVersion();
    updateBinaryFormatVersion();

    setCharset(DEFAULT_CHARSET);
    setDateFormat(DEFAULT_DATE_FORMAT);
    setDateTimeFormat(DEFAULT_DATETIME_FORMAT);
    setLocaleLanguage(Locale.getDefault().getLanguage());
    setLocaleCountry(Locale.getDefault().getCountry());
    setTimeZone(TimeZone.getDefault());

    setPageSize(-1);
    setFreeListBoundary(-1);
    setMaxKeySize(-1);

    getContextConfiguration().setValue(OGlobalConfiguration.CLASS_MINIMUM_CLUSTERS,
        OGlobalConfiguration.CLASS_MINIMUM_CLUSTERS.getValueAsInteger()); // 0 = AUTOMATIC
    autoInitClusters();

    setRecordSerializerVersion(0);
    validation = getContextConfiguration().getValueAsBoolean(OGlobalConfiguration.DB_VALIDATION);
  }

  private void autoInitClusters() {
    if (getContextConfiguration().getValueAsInteger(OGlobalConfiguration.CLASS_MINIMUM_CLUSTERS) == 0) {
      final int cpus = Runtime.getRuntime().availableProcessors();
      getContextConfiguration().setValue(OGlobalConfiguration.CLASS_MINIMUM_CLUSTERS, cpus > 64 ? 64 : cpus);
    }
  }

}

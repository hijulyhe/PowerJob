package tech.powerjob.server.persistence.storage.impl;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.model.Filters;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tech.powerjob.server.extension.dfs.*;
import tech.powerjob.server.persistence.storage.AbstractDFsService;

import java.io.*;
import java.nio.file.Files;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * 使用 MongoDB GridFS 作为底层存储
 * 配置用法：oms.storage.dfs.mongodb.uri=mongodb+srv://zqq:No1Bug2Please3!@cluster0.wie54.gcp.mongodb.net/powerjob_daily?retryWrites=true&w=majority
 *
 * @author tjq
 * @since 2023/7/28
 */
@Slf4j
@Service
@ConditionalOnProperty(name = {"oms.storage.dfs.mongodb.uri", "spring.data.mongodb.uri"}, matchIfMissing = false)
@ConditionalOnMissingBean(DFsService.class)
public class GridFsService extends AbstractDFsService implements InitializingBean {

    private MongoDatabase db;
    private final Map<String, GridFSBucket> bucketCache = Maps.newConcurrentMap();
    private static final String TYPE_MONGO = "mongodb";

    private static final String SPRING_MONGO_DB_CONFIG_KEY = "spring.data.mongodb.uri";

    @Override
    public void store(StoreRequest storeRequest) throws IOException {
        GridFSBucket bucket = getBucket(storeRequest.getFileLocation().getBucket());
        try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(storeRequest.getLocalFile().toPath()))) {
            bucket.uploadFromStream(storeRequest.getFileLocation().getName(), bis);
        }
    }

    @Override
    public void download(DownloadRequest downloadRequest) throws IOException {
        GridFSBucket bucket = getBucket(downloadRequest.getFileLocation().getBucket());
        try (GridFSDownloadStream gis = bucket.openDownloadStream(downloadRequest.getFileLocation().getName());
             BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(downloadRequest.getTarget().toPath()))
        ) {
            byte[] buffer = new byte[1024];
            int bytes = 0;
            while ((bytes = gis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytes);
            }
            bos.flush();
        }
    }

    @Override
    public Optional<FileMeta> fetchFileMeta(FileLocation fileLocation) throws IOException {
        GridFSBucket bucket = getBucket(fileLocation.getBucket());
        GridFSFindIterable files = bucket.find(Filters.eq("filename", fileLocation.getName()));
        GridFSFile first = files.first();
        if (first == null) {
            return Optional.empty();
        }
        return Optional.of(new FileMeta()
                .setLength(first.getLength())
                .setMetaInfo(first.getMetadata()));
    }

    @Override
    public void cleanExpiredFiles(String bucketName, int days) {
        Stopwatch sw = Stopwatch.createStarted();

        Date date = DateUtils.addDays(new Date(), -days);
        GridFSBucket bucket = getBucket(bucketName);
        Bson filter = Filters.lt("uploadDate", date);

        // 循环删除性能很差？我猜你肯定没看过官方实现[狗头]：org.springframework.data.mongodb.gridfs.GridFsTemplate.delete
        bucket.find(filter).forEach(gridFSFile -> {
            ObjectId objectId = gridFSFile.getObjectId();
            try {
                bucket.delete(objectId);
                log.info("[GridFsService] deleted {}#{}", bucketName, objectId);
            }catch (Exception e) {
                log.error("[GridFsService] deleted {}#{} failed.", bucketName, objectId, e);
            }
        });
        log.info("[GridFsService] clean bucket({}) successfully, delete all files before {}, using {}.", bucketName, date, sw.stop());
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        String uri = parseMongoUri(environment);
        log.info("[GridFsService] mongoDB uri: {}", uri);
        if (StringUtils.isEmpty(uri)) {
            log.warn("[GridFsService] uri is empty, GridFsService is off now!");
            return;
        }

        ConnectionString connectionString = new ConnectionString(uri);
        MongoClient mongoClient = MongoClients.create(connectionString);
        db = mongoClient.getDatabase(Optional.ofNullable(connectionString.getDatabase()).orElse("pj"));

        turnOn();

        log.info("[GridFsService] turn on mongodb GridFs as storage layer.");
    }

    private GridFSBucket getBucket(String bucketName) {
        return bucketCache.computeIfAbsent(bucketName, ignore -> GridFSBuckets.create(db, bucketName));
    }

    static String parseMongoUri(Environment environment) {
        // 优先从新的规则读取
        String uri = fetchProperty(environment, TYPE_MONGO, "uri");
        if (StringUtils.isNotEmpty(uri)) {
            return uri;
        }
        // 兼容 4.3.3 前的逻辑，读取 SpringMongoDB 配置
        return environment.getProperty(SPRING_MONGO_DB_CONFIG_KEY);
    }
}
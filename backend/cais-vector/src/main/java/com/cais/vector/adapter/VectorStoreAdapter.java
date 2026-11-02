package com.cais.vector.adapter;

import java.util.List;

/** 向量库控制面端口；memory、Milvus 与未选型 external 模式均显式区分。 */
public interface VectorStoreAdapter {
    void createCollection(CollectionSpec spec);

    void registerEmbeddingBatch(String collectionId, List<String> corpusIds, String modelVersion);

    void requestIndexRebuild(String collectionId, String maintenanceWindow);

    VectorStoreReadiness readiness();

    record CollectionSpec(String collectionId, String collectionName, int dimension, String metricType) { }

    record VectorStoreReadiness(String mode, boolean connected, String endpointHint) { }
}

/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.mongo.impl;

import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.*;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.*;
import com.mongodb.reactivestreams.client.gridfs.GridFSBucket;
import com.mongodb.reactivestreams.client.gridfs.GridFSBuckets;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.*;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.future.PromiseInternal;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.Shareable;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.mongo.BulkWriteOptions;
import io.vertx.ext.mongo.CountOptions;
import io.vertx.ext.mongo.CreateCollectionOptions;
import io.vertx.ext.mongo.FindOptions;
import io.vertx.ext.mongo.IndexModel;
import io.vertx.ext.mongo.IndexOptions;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.mongo.UpdateOptions;
import io.vertx.ext.mongo.*;
import io.vertx.ext.mongo.impl.codec.json.JsonObjectCodec;
import io.vertx.ext.mongo.impl.config.MongoClientOptionsParser;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.reactivestreams.Publisher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.vertx.ext.mongo.impl.Utils.ID_FIELD;
import static io.vertx.ext.mongo.impl.Utils.setHandler;
import static java.util.Objects.requireNonNull;

/**
 * The implementation of the {@link io.vertx.ext.mongo.MongoClient}. This implementation is based on the async driver
 * provided by Mongo.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class MongoClientImpl implements io.vertx.ext.mongo.MongoClient, Closeable {
  private static final UpdateOptions DEFAULT_UPDATE_OPTIONS = new UpdateOptions();
  private static final FindOptions DEFAULT_FIND_OPTIONS = new FindOptions();
  private static final AggregateOptions DEFAULT_AGGREGATE_OPTIONS = new AggregateOptions();
  private static final BulkWriteOptions DEFAULT_BULK_WRITE_OPTIONS = new BulkWriteOptions();

  private static final String DS_LOCAL_MAP_NAME = "__vertx.MongoClient.datasources";
  public static final String COLLECTION_CANNOT_BE_NULL = "collection cannot be null";
  public static final String QUERY_CANNOT_BE_NULL = "query cannot be null";
  public static final String FIELD_NAME_CANNOT_BE_NULL = "fieldName cannot be null";
  public static final String UPDATE_CANNOT_BE_NULL = "update cannot be null";
  public static final String OPTIONS_CANNOT_BE_NULL = "options cannot be null";
  public static final String PIPELINE_CANNOT_BE_NULL = "pipeline cannot be null";
  public static final String FIND_OPTIONS_CANNOT_BE_NULL = "find options cannot be null";

  private final VertxInternal vertx;
  private final ContextInternal creatingContext;
  protected com.mongodb.reactivestreams.client.MongoClient mongo;

  private final MongoHolder holder;
  private final boolean useObjectId;

  public MongoClientImpl(Vertx vertx, JsonObject config, String dataSourceName) {
    Objects.requireNonNull(vertx);
    Objects.requireNonNull(config);
    Objects.requireNonNull(dataSourceName);
    this.vertx = (VertxInternal) vertx;
    this.creatingContext = this.vertx.getOrCreateContext();
    this.holder = lookupHolder(dataSourceName, config);
    this.mongo = holder.mongo(vertx);
    this.useObjectId = config.getBoolean("useObjectId", false);

    creatingContext.addCloseHook(this);
  }

  public MongoClientImpl(Vertx vertx, JsonObject config, String dataSourceName, MongoClientSettings settings) {
    Objects.requireNonNull(vertx);
    Objects.requireNonNull(config);
    Objects.requireNonNull(dataSourceName);
    Objects.requireNonNull(settings);
    this.vertx = (VertxInternal) vertx;
    this.creatingContext = this.vertx.getOrCreateContext();
    this.holder = lookupHolder(dataSourceName, config);
    this.mongo = holder.mongo(vertx, settings);
    this.useObjectId = config.getBoolean("useObjectId", false);

    creatingContext.addCloseHook(this);
  }

  @GenIgnore
  public static <T> DistinctPublisher<T> setDistinctOptions(DistinctPublisher<T> distinctPublisher, DistinctOptions distinctOptions) {
    if (distinctOptions != null && distinctOptions.getCollation() != null) {
      distinctPublisher.collation(distinctOptions.getCollation().toMongoDriverObject());
    }
    return distinctPublisher;
  }

  @Override
  public void close(Promise<Void> completionHandler) {
    holder.close();
    completionHandler.complete();
  }

  @Override
  public Future<Void> close() {
    holder.close();
    creatingContext.removeCloseHook(this);
    return vertx.getOrCreateContext().succeededFuture();
  }

  @Override
  public MongoClient save(String collection, JsonObject document, Handler<AsyncResult<String>> resultHandler) {
    Future<String> future = save(collection, document);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable String> save(String collection, JsonObject document) {
    return saveWithOptions(collection, document, null);
  }

  @Override
  public MongoClient saveWithOptions(String collection, JsonObject document, @Nullable WriteOption writeOption, Handler<AsyncResult<String>> resultHandler) {
    Future<String> future = saveWithOptions(collection, document, writeOption);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable String> saveWithOptions(String collection, JsonObject document, @Nullable WriteOption writeOption) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(document, "document cannot be null");

    MongoCollection<JsonObject> coll = getCollection(collection, writeOption);
    Object id = document.getValue(ID_FIELD);

    if (id == null) {
      Promise<Void> promise = vertx.promise();
      coll.insertOne(document).subscribe(new CompletionSubscriber<>(promise));
      return promise.future().map(v -> useObjectId ? document.getJsonObject(ID_FIELD).getString(JsonObjectCodec.OID_FIELD) : document.getString(ID_FIELD));
    }

    JsonObject filter = new JsonObject();
    JsonObject encodedDocument = encodeKeyWhenUseObjectId(document);
    filter.put(ID_FIELD, encodedDocument.getValue(ID_FIELD));

    ReplaceOptions replaceOptions = new ReplaceOptions().upsert(true);

    Promise<Void> promise = vertx.promise();
    coll.replaceOne(wrap(filter), encodedDocument, replaceOptions).subscribe(new CompletionSubscriber<>(promise));
    return promise.future().mapEmpty();
  }

  @Override
  public MongoClient insert(String collection, JsonObject document, Handler<AsyncResult<String>> resultHandler) {
    Future<String> future = insert(collection, document);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable String> insert(String collection, JsonObject document) {
    return insertWithOptions(collection, document, null);
  }

  @Override
  public MongoClient insertWithOptions(String collection, JsonObject document, @Nullable WriteOption writeOption, Handler<AsyncResult<String>> resultHandler) {
    Future<String> future = insertWithOptions(collection, document, writeOption);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable String> insertWithOptions(String collection, JsonObject document, @Nullable WriteOption writeOption) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(document, "document cannot be null");

    JsonObject encodedDocument = encodeKeyWhenUseObjectId(document);
    boolean hasCustomId = document.containsKey(ID_FIELD);

    MongoCollection<JsonObject> coll = getCollection(collection, writeOption);

    Promise<Void> promise = vertx.promise();
    coll.insertOne(encodedDocument).subscribe(new CompletionSubscriber<>(promise));
    return promise.future().map(v -> hasCustomId ? null : decodeKeyWhenUseObjectId(encodedDocument).getString(ID_FIELD));
  }

  @Override
  public MongoClient updateCollection(String collection, JsonObject query, JsonObject update, Handler<AsyncResult<MongoClientUpdateResult>> resultHandler) {
    Future<MongoClientUpdateResult> future = updateCollection(collection, query, update);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientUpdateResult> updateCollection(String collection, JsonObject query, JsonObject update) {
    return updateCollectionWithOptions(collection, query, update, DEFAULT_UPDATE_OPTIONS);
  }

  @Override
  public MongoClient updateCollection(String collection, JsonObject query, JsonArray update, Handler<AsyncResult<MongoClientUpdateResult>> resultHandler) {
    Future<MongoClientUpdateResult> future = updateCollection(collection, query, update);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientUpdateResult> updateCollection(String collection, JsonObject query, JsonArray update) {
    return updateCollectionWithOptions(collection, query, update, DEFAULT_UPDATE_OPTIONS);
  }

  @Override
  public MongoClient updateCollectionWithOptions(String collection, JsonObject query, JsonObject update, UpdateOptions options, Handler<AsyncResult<MongoClientUpdateResult>> resultHandler) {
    Future<MongoClientUpdateResult> future = updateCollectionWithOptions(collection, query, update, options);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientUpdateResult> updateCollectionWithOptions(String collection, JsonObject query, JsonObject update, UpdateOptions options) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);
    requireNonNull(update, UPDATE_CANNOT_BE_NULL);
    requireNonNull(options, OPTIONS_CANNOT_BE_NULL);

    MongoCollection<JsonObject> coll = getCollection(collection, options.getWriteOption());
    Bson bquery = wrap(encodeKeyWhenUseObjectId(query));
    Bson bupdate = wrap(encodeKeyWhenUseObjectId(generateIdIfNeeded(query, update, options)));

    com.mongodb.client.model.UpdateOptions updateOptions = new com.mongodb.client.model.UpdateOptions().upsert(options.isUpsert());
    if (options.getArrayFilters() != null && !options.getArrayFilters().isEmpty()) {
      final List<Bson> bArrayFilters = new ArrayList<>(options.getArrayFilters().size());
      options.getArrayFilters().getList().forEach(entry -> bArrayFilters.add(wrap(JsonObject.mapFrom(entry))));
      updateOptions.arrayFilters(bArrayFilters);
    }
    if (options.getHint() != null) {
      updateOptions.hint(wrap(options.getHint()));
    }
    if (options.getHintString() != null && !options.getHintString().isEmpty()) {
      updateOptions.hintString(options.getHintString());
    }
    if (options.getCollation() != null) {
      updateOptions.collation(options.getCollation().toMongoDriverObject());
    }

    Publisher<UpdateResult> publisher;
    if (options.isMulti()) {
      publisher = coll.updateMany(bquery, bupdate, updateOptions);
    } else {
      publisher = coll.updateOne(bquery, bupdate, updateOptions);
    }

    Promise<UpdateResult> promise = vertx.promise();
    publisher.subscribe(new SingleResultSubscriber<>(promise));
    return promise.future().map(Utils::toMongoClientUpdateResult);
  }

  @Override
  public MongoClient updateCollectionWithOptions(String collection, JsonObject query, JsonArray update, UpdateOptions options, Handler<AsyncResult<@Nullable MongoClientUpdateResult>> resultHandler) {
    Future<MongoClientUpdateResult> future = updateCollectionWithOptions(collection, query, update, options);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientUpdateResult> updateCollectionWithOptions(String collection, JsonObject query, JsonArray pipeline, UpdateOptions options) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);
    requireNonNull(pipeline, PIPELINE_CANNOT_BE_NULL);
    requireNonNull(options, OPTIONS_CANNOT_BE_NULL);

    MongoCollection<JsonObject> coll = getCollection(collection, options.getWriteOption());
    Bson bquery = wrap(encodeKeyWhenUseObjectId(query));
    List<Bson> bpipeline = new ArrayList<>(pipeline.size());
    for (int i=0 ; i<pipeline.size() ; i++) {
      bpipeline.add(wrap(pipeline.getJsonObject(i)));
    }

    com.mongodb.client.model.UpdateOptions updateOptions = new com.mongodb.client.model.UpdateOptions().upsert(options.isUpsert());
    if (options.getArrayFilters() != null && !options.getArrayFilters().isEmpty()) {
      final List<Bson> bArrayFilters = new ArrayList<>(options.getArrayFilters().size());
      options.getArrayFilters().getList().forEach(entry -> bArrayFilters.add(wrap(JsonObject.mapFrom(entry))));
      updateOptions.arrayFilters(bArrayFilters);
    }
    if (options.getHint() != null) {
      updateOptions.hint(wrap(options.getHint()));
    }
    if (options.getHintString() != null && !options.getHintString().isEmpty()) {
      updateOptions.hintString(options.getHintString());
    }
    if (options.getCollation() != null) {
      updateOptions.collation(options.getCollation().toMongoDriverObject());
    }

    Publisher<UpdateResult> publisher = coll.updateMany(bquery, bpipeline, updateOptions);

    Promise<UpdateResult> promise = vertx.promise();
    publisher.subscribe(new SingleResultSubscriber<>(promise));
    return promise.future().map(Utils::toMongoClientUpdateResult);
  }

  private JsonObject generateIdIfNeeded(JsonObject query, JsonObject update, UpdateOptions options) {
    if (options.isUpsert() && !update.containsKey(ID_FIELD) && !useObjectId) {
      JsonObject setId = update.getJsonObject("$setOnInsert", new JsonObject());
      String id;

      //This seems odd, but if you filter based on _id, mongo expects the generated _id to match
      if (query.containsKey(ID_FIELD)) {
        id = query.getString(ID_FIELD);
      } else {
        id = JsonObjectCodec.generateHexObjectId();
      }
      setId.put(ID_FIELD, id);
      update.put("$setOnInsert", setId);
    }
    return update;
  }

  @Override
  public MongoClient replaceDocuments(String collection, JsonObject query, JsonObject replace, Handler<AsyncResult<MongoClientUpdateResult>> resultHandler) {
    Future<MongoClientUpdateResult> future = replaceDocumentsWithOptions(collection, query, replace, DEFAULT_UPDATE_OPTIONS);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientUpdateResult> replaceDocuments(String collection, JsonObject query, JsonObject replace) {
    return replaceDocumentsWithOptions(collection, query, replace, DEFAULT_UPDATE_OPTIONS);
  }

  @Override
  public MongoClient replaceDocumentsWithOptions(String collection, JsonObject query, JsonObject replace, UpdateOptions options, Handler<AsyncResult<MongoClientUpdateResult>> resultHandler) {
    Future<MongoClientUpdateResult> future = replaceDocumentsWithOptions(collection, query, replace, options);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientUpdateResult> replaceDocumentsWithOptions(String collection, JsonObject query, JsonObject replace, UpdateOptions options) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);
    requireNonNull(replace, UPDATE_CANNOT_BE_NULL);
    requireNonNull(options, OPTIONS_CANNOT_BE_NULL);

    MongoCollection<JsonObject> coll = getCollection(collection, options.getWriteOption());
    Bson bquery = wrap(encodeKeyWhenUseObjectId(query));
    com.mongodb.client.model.ReplaceOptions replaceOptions = new com.mongodb.client.model.ReplaceOptions().upsert(options.isUpsert());
    if (options.getHint() != null) {
      replaceOptions.hint(wrap(options.getHint()));
    }
    if (options.getHintString() != null && !options.getHintString().isEmpty()) {
      replaceOptions.hintString(options.getHintString());
    }
    if (options.getCollation() != null) {
      replaceOptions.collation(options.getCollation().toMongoDriverObject());
    }
    Promise<UpdateResult> promise = vertx.promise();
    coll.replaceOne(bquery, encodeKeyWhenUseObjectId(replace), replaceOptions).subscribe(new SingleResultSubscriber<>(promise));
    return promise.future().map(Utils::toMongoClientUpdateResult);
  }

  @Override
  public MongoClient find(String collection, JsonObject query, Handler<AsyncResult<List<JsonObject>>> resultHandler) {
    Future<List<JsonObject>> future = find(collection, query);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<List<JsonObject>> find(String collection, JsonObject query) {
    return findWithOptions(collection, query, DEFAULT_FIND_OPTIONS);
  }

  @Override
  public MongoClient findWithOptions(String collection, JsonObject query, FindOptions options, Handler<AsyncResult<List<JsonObject>>> resultHandler) {
    Future<List<JsonObject>> future = findWithOptions(collection, query, options);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<List<JsonObject>> findWithOptions(String collection, JsonObject query, FindOptions options) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);

    Promise<List<JsonObject>> promise = vertx.promise();
    doFind(collection, encodeKeyWhenUseObjectId(query), options)
      .subscribe(new MappingAndBufferingSubscriber<>(this::decodeKeyWhenUseObjectId, promise));
    return promise.future();
  }

  @Override
  public ReadStream<JsonObject> findBatch(String collection, JsonObject query) {
    return findBatchWithOptions(collection, query, DEFAULT_FIND_OPTIONS);
  }

  @Override
  public ReadStream<JsonObject> findBatchWithOptions(String collection, JsonObject query, FindOptions options) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);
    FindPublisher<JsonObject> view = doFind(collection, query, options);
    return new PublisherAdapter<>(vertx.getOrCreateContext(), view, options.getBatchSize());
  }

  @Override
  public MongoClient findOne(String collection, JsonObject query, @Nullable JsonObject fields, Handler<AsyncResult<JsonObject>> resultHandler) {
    Future<JsonObject> future = findOne(collection, query, fields);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable JsonObject> findOne(String collection, JsonObject query, @Nullable JsonObject fields) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);

    JsonObject encodedQuery = encodeKeyWhenUseObjectId(query);

    Bson bquery = wrap(encodedQuery);
    Bson bfields = wrap(fields);
    Promise<JsonObject> promise = vertx.promise();
    getCollection(collection).find(bquery).projection(bfields).first().subscribe(new SingleResultSubscriber<>(promise));
    return promise.future().map(object -> object == null ? null : decodeKeyWhenUseObjectId(object));
  }

  @Override
  public MongoClient findOneAndUpdate(String collection, JsonObject query, JsonObject update, Handler<AsyncResult<JsonObject>> resultHandler) {
    Future<JsonObject> future = findOneAndUpdate(collection, query, update);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable JsonObject> findOneAndUpdate(String collection, JsonObject query, JsonObject update) {
    return findOneAndUpdateWithOptions(collection, query, update, DEFAULT_FIND_OPTIONS, DEFAULT_UPDATE_OPTIONS);
  }

  @Override
  public MongoClient findOneAndUpdateWithOptions(String collection, JsonObject query, JsonObject update, FindOptions findOptions, UpdateOptions updateOptions, Handler<AsyncResult<JsonObject>> resultHandler) {
    Future<JsonObject> future = findOneAndUpdateWithOptions(collection, query, update, findOptions, updateOptions);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable JsonObject> findOneAndUpdateWithOptions(String collection, JsonObject query, JsonObject update, FindOptions findOptions, UpdateOptions updateOptions) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);
    requireNonNull(update, UPDATE_CANNOT_BE_NULL);
    requireNonNull(findOptions, FIND_OPTIONS_CANNOT_BE_NULL);
    requireNonNull(updateOptions, "update options cannot be null");

    JsonObject encodedQuery = encodeKeyWhenUseObjectId(query);

    Bson bquery = wrap(encodedQuery);
    Bson bupdate = wrap(update);
    FindOneAndUpdateOptions foauOptions = new FindOneAndUpdateOptions();
    foauOptions.sort(wrap(findOptions.getSort()));
    foauOptions.projection(wrap(findOptions.getFields()));
    foauOptions.upsert(updateOptions.isUpsert());
    foauOptions.returnDocument(updateOptions.isReturningNewDocument() ? ReturnDocument.AFTER : ReturnDocument.BEFORE);
    if (updateOptions.getArrayFilters() != null && !updateOptions.getArrayFilters().isEmpty()) {
      final List<Bson> bArrayFilters = new ArrayList<>(updateOptions.getArrayFilters().size());
      updateOptions.getArrayFilters().getList().forEach(entry -> bArrayFilters.add(wrap(JsonObject.mapFrom(entry))));
      foauOptions.arrayFilters(bArrayFilters);
    }
    if (findOptions.getHint() != null) {
      foauOptions.hint(wrap(findOptions.getHint()));
    }
    if (findOptions.getHintString() != null && !findOptions.getHintString().isEmpty()) {
      foauOptions.hintString(findOptions.getHintString());
    }
    if (updateOptions.getHint() != null) {
      foauOptions.hint(wrap(updateOptions.getHint()));
    }
    if (updateOptions.getHintString() != null && !updateOptions.getHintString().isEmpty()) {
      foauOptions.hintString(updateOptions.getHintString());
    }
    if(findOptions.getCollation() != null) {
      foauOptions.collation(findOptions.getCollation().toMongoDriverObject());
    }
    if(updateOptions.getCollation() != null) {
      foauOptions.collation(updateOptions.getCollation().toMongoDriverObject());
    }

    MongoCollection<JsonObject> coll = getCollection(collection);
    Promise<JsonObject> promise = vertx.promise();
    coll.findOneAndUpdate(bquery, bupdate, foauOptions).subscribe(new SingleResultSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient findOneAndReplace(String collection, JsonObject query, JsonObject replace, Handler<AsyncResult<JsonObject>> resultHandler) {
    Future<JsonObject> future = findOneAndReplace(collection, query, replace);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable JsonObject> findOneAndReplace(String collection, JsonObject query, JsonObject replace) {
    return findOneAndReplaceWithOptions(collection, query, replace, DEFAULT_FIND_OPTIONS, DEFAULT_UPDATE_OPTIONS);
  }

  @Override
  public MongoClient findOneAndReplaceWithOptions(String collection, JsonObject query, JsonObject replace, FindOptions findOptions, UpdateOptions updateOptions, Handler<AsyncResult<JsonObject>> resultHandler) {
    Future<JsonObject> future = findOneAndReplaceWithOptions(collection, query, replace, findOptions, updateOptions);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable JsonObject> findOneAndReplaceWithOptions(String collection, JsonObject query, JsonObject replace, FindOptions findOptions, UpdateOptions updateOptions) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);
    requireNonNull(findOptions, FIND_OPTIONS_CANNOT_BE_NULL);
    requireNonNull(updateOptions, "update options cannot be null");

    JsonObject encodedQuery = encodeKeyWhenUseObjectId(query);

    Bson bquery = wrap(encodedQuery);
    FindOneAndReplaceOptions foarOptions = new FindOneAndReplaceOptions();
    foarOptions.sort(wrap(findOptions.getSort()));
    foarOptions.projection(wrap(findOptions.getFields()));
    foarOptions.upsert(updateOptions.isUpsert());
    foarOptions.returnDocument(updateOptions.isReturningNewDocument() ? ReturnDocument.AFTER : ReturnDocument.BEFORE);
    if (findOptions.getHint() != null) {
      foarOptions.hint(wrap(findOptions.getHint()));
    }
    if (findOptions.getHintString() != null && !findOptions.getHintString().isEmpty()) {
      foarOptions.hintString(findOptions.getHintString());
    }
    if (updateOptions.getHint() != null) {
      foarOptions.hint(wrap(updateOptions.getHint()));
    }
    if (updateOptions.getHintString() != null && !updateOptions.getHintString().isEmpty()) {
      foarOptions.hintString(updateOptions.getHintString());
    }

    if(findOptions.getCollation() != null) {
      foarOptions.collation(findOptions.getCollation().toMongoDriverObject());
    }

    MongoCollection<JsonObject> coll = getCollection(collection);
    Promise<JsonObject> promise = vertx.promise();
    coll.findOneAndReplace(bquery, replace, foarOptions).subscribe(new SingleResultSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient findOneAndDelete(String collection, JsonObject query, Handler<AsyncResult<JsonObject>> resultHandler) {
    Future<JsonObject> future = findOneAndDelete(collection, query);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable JsonObject> findOneAndDelete(String collection, JsonObject query) {
    return findOneAndDeleteWithOptions(collection, query, DEFAULT_FIND_OPTIONS);
  }

  @Override
  public MongoClient findOneAndDeleteWithOptions(String collection, JsonObject query, FindOptions findOptions, Handler<AsyncResult<JsonObject>> resultHandler) {
    Future<JsonObject> future = findOneAndDeleteWithOptions(collection, query, findOptions);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable JsonObject> findOneAndDeleteWithOptions(String collection, JsonObject query, FindOptions findOptions) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);
    requireNonNull(findOptions, FIND_OPTIONS_CANNOT_BE_NULL);

    JsonObject encodedQuery = encodeKeyWhenUseObjectId(query);

    Bson bquery = wrap(encodedQuery);
    FindOneAndDeleteOptions foadOptions = new FindOneAndDeleteOptions();
    foadOptions.sort(wrap(findOptions.getSort()));
    foadOptions.projection(wrap(findOptions.getFields()));

    if (findOptions.getHint() != null) {
      foadOptions.hint(wrap(findOptions.getHint()));
    }
    if (findOptions.getHintString() != null && !findOptions.getHintString().isEmpty()) {
      foadOptions.hintString(findOptions.getHintString());
    }

    if(findOptions.getCollation() != null) {
      foadOptions.collation(findOptions.getCollation().toMongoDriverObject());
    }

    MongoCollection<JsonObject> coll = getCollection(collection);
    Promise<JsonObject> promise = vertx.promise();
    coll.findOneAndDelete(bquery, foadOptions).subscribe(new SingleResultSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient count(String collection, JsonObject query, Handler<AsyncResult<Long>> resultHandler) {
    Future<Long> future = countWithOptions(collection, query, null);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public MongoClient countWithOptions(String collection, JsonObject query, CountOptions countOptions, Handler<AsyncResult<Long>> resultHandler) {
    Future<Long> future = countWithOptions(collection, query, countOptions);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<Long> count(String collection, JsonObject query) {
    return countWithOptions(collection, query, null);
  }

  @Override
  public Future<Long> countWithOptions(String collection, JsonObject query, CountOptions countOptions) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);

    Bson bquery = wrap(encodeKeyWhenUseObjectId(query));
    MongoCollection<JsonObject> coll = getCollection(collection);
    Promise<Long> promise = vertx.promise();
    Publisher<Long> countPublisher = countOptions != null
      ? coll.countDocuments(bquery, countOptions.toMongoDriverObject())
      : coll.countDocuments(bquery);
    countPublisher.subscribe(new SingleResultSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient removeDocuments(String collection, JsonObject query, Handler<AsyncResult<MongoClientDeleteResult>> resultHandler) {
    Future<MongoClientDeleteResult> future = removeDocuments(collection, query);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientDeleteResult> removeDocuments(String collection, JsonObject query) {
    return removeDocumentsWithOptions(collection, query, null);
  }

  @Override
  public MongoClient removeDocumentsWithOptions(String collection, JsonObject query, @Nullable WriteOption writeOption, Handler<AsyncResult<MongoClientDeleteResult>> resultHandler) {
    Future<MongoClientDeleteResult> future = removeDocumentsWithOptions(collection, query, writeOption);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientDeleteResult> removeDocumentsWithOptions(String collection, JsonObject query, @Nullable WriteOption writeOption) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);

    MongoCollection<JsonObject> coll = getCollection(collection, writeOption);
    Bson bquery = wrap(encodeKeyWhenUseObjectId(query));
    Promise<DeleteResult> promise = vertx.promise();
    coll.deleteMany(bquery).subscribe(new SingleResultSubscriber<>(promise));
    return promise.future().map(Utils::toMongoClientDeleteResult);
  }

  @Override
  public MongoClient removeDocument(String collection, JsonObject query, Handler<AsyncResult<MongoClientDeleteResult>> resultHandler) {
    Future<MongoClientDeleteResult> future = removeDocument(collection, query);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientDeleteResult> removeDocument(String collection, JsonObject query) {
    return removeDocumentWithOptions(collection, query, null);
  }

  @Override
  public MongoClient removeDocumentWithOptions(String collection, JsonObject query, @Nullable WriteOption writeOption, Handler<AsyncResult<MongoClientDeleteResult>> resultHandler) {
    Future<MongoClientDeleteResult> future = removeDocumentWithOptions(collection, query, writeOption);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientDeleteResult> removeDocumentWithOptions(String collection, JsonObject query, @Nullable WriteOption writeOption) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);

    MongoCollection<JsonObject> coll = getCollection(collection, writeOption);
    Bson bquery = wrap(encodeKeyWhenUseObjectId(query));
    Promise<DeleteResult> promise = vertx.promise();
    coll.deleteOne(bquery).subscribe(new SingleResultSubscriber<>(promise));
    return promise.future().map(Utils::toMongoClientDeleteResult);
  }

  @Override
  public MongoClient bulkWrite(String collection, List<BulkOperation> operations, Handler<AsyncResult<MongoClientBulkWriteResult>> resultHandler) {
    Future<MongoClientBulkWriteResult> future = bulkWrite(collection, operations);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientBulkWriteResult> bulkWrite(String collection, List<BulkOperation> operations) {
    return bulkWriteWithOptions(collection, operations, DEFAULT_BULK_WRITE_OPTIONS);
  }

  @Override
  public MongoClient bulkWriteWithOptions(String collection, List<BulkOperation> operations, BulkWriteOptions bulkWriteOptions, Handler<AsyncResult<MongoClientBulkWriteResult>> resultHandler) {
    Future<MongoClientBulkWriteResult> future = bulkWriteWithOptions(collection, operations, bulkWriteOptions);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable MongoClientBulkWriteResult> bulkWriteWithOptions(String collection, List<BulkOperation> operations, BulkWriteOptions bulkWriteOptions) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(operations, "operations cannot be null");
    requireNonNull(bulkWriteOptions, "bulkWriteOptions cannot be null");
    MongoCollection<JsonObject> coll = getCollection(collection, bulkWriteOptions.getWriteOption());
    List<WriteModel<JsonObject>> bulkOperations = convertBulkOperations(operations);
    com.mongodb.client.model.BulkWriteOptions options = new com.mongodb.client.model.BulkWriteOptions().ordered(bulkWriteOptions.isOrdered());
    Promise<BulkWriteResult> promise = vertx.promise();
    coll.bulkWrite(bulkOperations, options).subscribe(new SingleResultSubscriber<>(promise));
    return promise.future().map(Utils::toMongoClientBulkWriteResult);
  }

  private List<WriteModel<JsonObject>> convertBulkOperations(List<BulkOperation> operations) {
    List<WriteModel<JsonObject>> result = new ArrayList<>(operations.size());
    for (BulkOperation bulkOperation : operations) {
      switch (bulkOperation.getType()) {
        case DELETE:
          Bson bsonFilter = toBson(encodeKeyWhenUseObjectId(bulkOperation.getFilter()));
          DeleteOptions deleteOptions = new DeleteOptions();
          if (bulkOperation.getHint() != null) {
            deleteOptions.hint(toBson(bulkOperation.getHint()));
          }
          if (bulkOperation.getHintString() != null && !bulkOperation.getHintString().isEmpty()) {
            deleteOptions.hintString(bulkOperation.getHintString());
          }
          if (bulkOperation.getCollation() != null) {
            deleteOptions.collation(bulkOperation.getCollation().toMongoDriverObject());
          }
          if (bulkOperation.isMulti()) {
            result.add(new DeleteManyModel<>(bsonFilter, deleteOptions));
          } else {
            result.add(new DeleteOneModel<>(bsonFilter, deleteOptions));
          }
          break;
        case INSERT:
          result.add(new InsertOneModel<>(encodeKeyWhenUseObjectId(bulkOperation.getDocument())));
          break;
        case REPLACE:
          ReplaceOptions replaceOptions = new ReplaceOptions();
          if (bulkOperation.getCollation() != null) {
            replaceOptions.collation(bulkOperation.getCollation().toMongoDriverObject());
          }
          if (bulkOperation.getHint() != null) {
            replaceOptions.hint(toBson(bulkOperation.getHint()));
          }
          if (bulkOperation.getHintString() != null && !bulkOperation.getHintString().isEmpty()) {
            replaceOptions.hintString(bulkOperation.getHintString());
          }
          result.add(new ReplaceOneModel<>(toBson(encodeKeyWhenUseObjectId(bulkOperation.getFilter())), bulkOperation.getDocument(),
            replaceOptions.upsert(bulkOperation.isUpsert())));
          break;
        case UPDATE:
          Bson filter = toBson(encodeKeyWhenUseObjectId(bulkOperation.getFilter()));
          Bson document = toBson(encodeKeyWhenUseObjectId(bulkOperation.getDocument()));
          com.mongodb.client.model.UpdateOptions updateOptions = new com.mongodb.client.model.UpdateOptions()
            .upsert(bulkOperation.isUpsert());
          if (bulkOperation.getCollation() != null) {
            updateOptions.collation(bulkOperation.getCollation().toMongoDriverObject());
          }
          if (bulkOperation.getHint() != null) {
            updateOptions.hint(toBson(bulkOperation.getHint()));
          }
          if (bulkOperation.getHintString() != null && !bulkOperation.getHintString().isEmpty()) {
            updateOptions.hintString(bulkOperation.getHintString());
          }
          if (bulkOperation.isMulti()) {
            result.add(new UpdateManyModel<>(filter, document, updateOptions));
          } else {
            result.add(new UpdateOneModel<>(filter, document, updateOptions));
          }
          break;
        default:
          throw new IllegalArgumentException("Unknown bulk operation type: " + bulkOperation.getClass());
      }
    }
    return result;
  }

  @Override
  public MongoClient createCollection(String collectionName, Handler<AsyncResult<Void>> resultHandler) {
    Future<Void> future = createCollection(collectionName);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public MongoClient createCollectionWithOptions(String collectionName, CreateCollectionOptions collectionOptions, Handler<AsyncResult<Void>> resultHandler) {
    Future<Void> future = createCollectionWithOptions(collectionName, collectionOptions);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<Void> createCollection(String collectionName) {
    requireNonNull(collectionName, "collectionName cannot be null");

    Promise<Void> promise = vertx.promise();
    holder.db.createCollection(collectionName).subscribe(new CompletionSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public Future<Void> createCollectionWithOptions(String collectionName, CreateCollectionOptions collectionOptions) {
    requireNonNull(collectionName, "collectionName cannot be null");

    Promise<Void> promise = vertx.promise();
    holder.db.createCollection(collectionName, collectionOptions.toMongoDriverObject())
      .subscribe(new CompletionSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient getCollections(Handler<AsyncResult<List<String>>> resultHandler) {
    Future<List<String>> future = getCollections();
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<List<String>> getCollections() {
    Promise<List<String>> promise = vertx.promise();
    holder.db.listCollectionNames().subscribe(new BufferingSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient dropCollection(String collection, Handler<AsyncResult<Void>> resultHandler) {
    Future<Void> future = dropCollection(collection);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<Void> dropCollection(String collection) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);

    MongoCollection<JsonObject> coll = getCollection(collection);
    Promise<Void> promise = vertx.promise();
    coll.drop().subscribe(new CompletionSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient createIndex(String collection, JsonObject key, Handler<AsyncResult<Void>> resultHandler) {
    Future<Void> future = createIndex(collection, key);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<Void> createIndex(String collection, JsonObject key) {
    return createIndexWithOptions(collection, key, new IndexOptions());
  }

  @Override
  public MongoClient createIndexWithOptions(String collection, JsonObject key, IndexOptions options, Handler<AsyncResult<Void>> resultHandler) {
    Future<Void> future = createIndexWithOptions(collection, key, options);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<Void> createIndexWithOptions(String collection, JsonObject key, IndexOptions options) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(key, FIELD_NAME_CANNOT_BE_NULL);

    MongoCollection<JsonObject> coll = getCollection(collection);
    com.mongodb.client.model.IndexOptions driverOpts = mongoIndexOptions(options);
    Promise<Void> promise = vertx.promise();
    coll.createIndex(wrap(key), driverOpts).subscribe(new CompletionSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient createIndexes(String collection, List<IndexModel> indexes, Handler<AsyncResult<Void>> resultHandler) {
    Future<Void> future = createIndexes(collection, indexes);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<Void> createIndexes(String collection, List<IndexModel> indexes) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);

    final List<com.mongodb.client.model.IndexModel> transformIndexes = indexes.stream().map(it -> {
      if (it.getOptions() != null)
        return new com.mongodb.client.model.IndexModel(wrap(it.getKey()), mongoIndexOptions(it.getOptions()));
      else return new com.mongodb.client.model.IndexModel(wrap(it.getKey()));
    }).collect(Collectors.toList());

    Promise<Void> promise = vertx.promise();
    getCollection(collection).createIndexes(transformIndexes).subscribe(new CompletionSubscriber<>(promise));
    return promise.future();
  }

  private static Bson toBson(@Nullable JsonObject json) {
    return json == null ? null : BsonDocument.parse(json.encode());
  }

  @Override
  public MongoClient listIndexes(String collection, Handler<AsyncResult<JsonArray>> resultHandler) {
    Future<JsonArray> future = listIndexes(collection);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<JsonArray> listIndexes(String collection) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    MongoCollection<JsonObject> coll = getCollection(collection);
    Promise<List<JsonObject>> promise = vertx.promise();
    coll.listIndexes(JsonObject.class).subscribe(new BufferingSubscriber<>(promise));
    return promise.future().map(JsonArray::new);
  }

  @Override
  public MongoClient dropIndex(String collection, String indexName, Handler<AsyncResult<Void>> resultHandler) {
    Future<Void> future = dropIndex(collection, indexName);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<Void> dropIndex(String collection, String indexName) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(indexName, "indexName cannot be null");
    MongoCollection<JsonObject> coll = getCollection(collection);
    Promise<Void> promise = vertx.promise();
    coll.dropIndex(indexName).subscribe(new CompletionSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient runCommand(String commandName, JsonObject command, Handler<AsyncResult<JsonObject>> resultHandler) {
    Future<JsonObject> future = runCommand(commandName, command);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<@Nullable JsonObject> runCommand(String commandName, JsonObject command) {
    requireNonNull(commandName, "commandName cannot be null");
    requireNonNull(command, "command cannot be null");
    // The command name must be the first entry in the bson, so to ensure this we must recreate and add the command
    // name as first (JsonObject is internally ordered)
    JsonObject json = new JsonObject();
    Object commandVal = command.getValue(commandName);
    if (commandVal == null) {
      throw new IllegalArgumentException("commandBody does not contain key for " + commandName);
    }
    json.put(commandName, commandVal);
    command.forEach(entry -> {
      if (!entry.getKey().equals(commandName)) {
        json.put(entry.getKey(), entry.getValue());
      }
    });

    Promise<JsonObject> promise = vertx.promise();
    holder.db.runCommand(wrap(json), JsonObject.class).subscribe(new SingleResultSubscriber<>(promise));
    return promise.future();
  }

  @Override
  public MongoClient distinct(String collection, String fieldName, String resultClassname, Handler<AsyncResult<JsonArray>> resultHandler) {
    return distinct(collection, fieldName, resultClassname, null, resultHandler);
  }

  @Override
  public MongoClient distinct(String collection, String fieldName, String resultClassname, DistinctOptions distinctOptions, Handler<AsyncResult<JsonArray>> resultHandler) {
    Future<JsonArray> future = distinct(collection, fieldName, resultClassname, distinctOptions);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<JsonArray> distinct(String collection, String fieldName, String resultClassname) {
    return distinct(collection, fieldName, resultClassname, (DistinctOptions) null);
  }

  @Override
  public Future<JsonArray> distinct(String collection, String fieldName, String resultClassname, DistinctOptions distinctOptions) {
    return distinctWithQuery(collection, fieldName, resultClassname, new JsonObject(), distinctOptions);
  }

  @Override
  public MongoClient distinctWithQuery(String collection, String fieldName, String resultClassname, JsonObject query, Handler<AsyncResult<JsonArray>> resultHandler) {
    return distinctWithQuery(collection, fieldName, resultClassname, query, null, resultHandler);
  }

  @Override
  public MongoClient distinctWithQuery(String collection, String fieldName, String resultClassname, JsonObject query, DistinctOptions distinctOptions, Handler<AsyncResult<JsonArray>> resultHandler) {
    Future<JsonArray> future = distinctWithQuery(collection, fieldName, resultClassname, query, distinctOptions);
    setHandler(future, resultHandler);
    return this;
  }

  @Override
  public Future<JsonArray> distinctWithQuery(String collection, String fieldName, String resultClassname, JsonObject query) {
    return distinctWithQuery(collection, fieldName, resultClassname, query, (DistinctOptions) null);
  }

  @Override
  public Future<JsonArray> distinctWithQuery(String collection, String fieldName, String resultClassname, JsonObject query, DistinctOptions distinctOptions) {
    try {
      PromiseInternal<List<Object>> promise = vertx.promise();
      findDistinctValuesWithQuery(collection, fieldName, resultClassname, query, distinctOptions).subscribe(new BufferingSubscriber<>(promise));
      return promise.future().map(JsonArray::new);
    } catch (ClassNotFoundException e) {
      return vertx.getOrCreateContext().failedFuture(e);
    }
  }

  @Override
  public ReadStream<JsonObject> distinctBatch(String collection, String fieldName, String resultClassname) {
    return distinctBatch(collection, fieldName, resultClassname, null);
  }

  @Override
  public ReadStream<JsonObject> distinctBatch(String collection, String fieldName, String resultClassname, DistinctOptions distinctOptions) {
    return distinctBatchWithQuery(collection, fieldName, resultClassname, new JsonObject(), distinctOptions);
  }

  @Override
  public ReadStream<JsonObject> distinctBatchWithQuery(String collection, String fieldName, String resultClassname, JsonObject query) {
    return distinctBatchWithQuery(collection, fieldName, resultClassname, query, null);
  }

  @Override
  public ReadStream<JsonObject> distinctBatchWithQuery(String collection, String fieldName, String resultClassname, JsonObject query, DistinctOptions distinctOptions) {
    return distinctBatchWithQuery(collection, fieldName, resultClassname, query, FindOptions.DEFAULT_BATCH_SIZE, null);
  }

  @Override
  public ReadStream<JsonObject> distinctBatchWithQuery(String collection, String fieldName, String resultClassname, JsonObject query, int batchSize) {
    return distinctBatchWithQuery(collection, fieldName, resultClassname, query, batchSize, null);
  }

  @Override
  public ReadStream<JsonObject> distinctBatchWithQuery(String collection, String fieldName, String resultClassname, JsonObject query, int batchSize, DistinctOptions distinctOptions) {
    try {
      DistinctPublisher<?> distinctValuesWithQuery = findDistinctValuesWithQuery(collection, fieldName, resultClassname, query, distinctOptions);
      PublisherAdapter<?> readStream = new PublisherAdapter<>(vertx.getOrCreateContext(), distinctValuesWithQuery, batchSize);
      return new MappingStream<>(readStream, value -> new JsonObject().put(fieldName, value));
    } catch (ClassNotFoundException e) {
      return new FailedStream(e);
    }
  }

  @Override
  public MongoClient createDefaultGridFsBucketService(Handler<AsyncResult<MongoGridFsClient>> resultHandler) {
    return this.createGridFsBucketService("fs", resultHandler);
  }

  @Override
  public Future<MongoGridFsClient> createDefaultGridFsBucketService() {
    Promise<MongoGridFsClient> promise = Promise.promise();
    createDefaultGridFsBucketService(promise);
    return promise.future();
  }

  @Override
  public MongoClient createGridFsBucketService(String bucketName, Handler<AsyncResult<MongoGridFsClient>> resultHandler) {
    MongoGridFsClientImpl impl = new MongoGridFsClientImpl(vertx, this, getGridFSBucket(bucketName));
    resultHandler.handle(Future.succeededFuture(impl));
    return this;
  }

  @Override
  public Future<MongoGridFsClient> createGridFsBucketService(String bucketName) {
    Promise<MongoGridFsClient> promise = Promise.promise();
    createGridFsBucketService(bucketName, promise);
    return promise.future();
  }

  private GridFSBucket getGridFSBucket(String bucketName) {
    return GridFSBuckets.create(holder.db, bucketName);
  }

  @Override
  public ReadStream<JsonObject> aggregate(final String collection, final JsonArray pipeline) {
    return aggregateWithOptions(collection, pipeline, DEFAULT_AGGREGATE_OPTIONS);
  }

  @Override
  public ReadStream<JsonObject> aggregateWithOptions(final String collection, final JsonArray pipeline, final AggregateOptions options) {
    AggregatePublisher<JsonObject> view = doAggregate(collection, pipeline, options);
    return new PublisherAdapter<>(vertx.getOrCreateContext(), view, options.getBatchSize());
  }

  @Override
  public ReadStream<ChangeStreamDocument<JsonObject>> watch(final String collection, final JsonArray pipeline, boolean withUpdatedDoc, int batchSize) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(pipeline, PIPELINE_CANNOT_BE_NULL);
    MongoCollection<JsonObject> coll = getCollection(collection);
    final List<Bson> bpipeline = new ArrayList<>(pipeline.size());
    pipeline.getList().forEach(entry -> bpipeline.add(wrap(JsonObject.mapFrom(entry))));
    ChangeStreamPublisher<JsonObject> changeStreamPublisher = coll.watch(bpipeline, JsonObject.class);
    if (withUpdatedDoc) {
      // By default, only "insert" and "replace" operations return fullDocument
      // Following setting is for "update" operation to return fullDocument
      changeStreamPublisher.fullDocument(FullDocument.UPDATE_LOOKUP);
    }
    if (batchSize < 1) {
      batchSize = 1;
    }
    return new PublisherAdapter<>(vertx.getOrCreateContext(), changeStreamPublisher, batchSize);
  }

  private DistinctPublisher<?> findDistinctValuesWithQuery(String collection, String fieldName, String resultClassname, JsonObject query, DistinctOptions distinctOptions) throws ClassNotFoundException {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(fieldName, FIELD_NAME_CANNOT_BE_NULL);
    requireNonNull(query, QUERY_CANNOT_BE_NULL);

    JsonObject encodedQuery = encodeKeyWhenUseObjectId(query);

    Bson bquery = wrap(encodedQuery);

    MongoCollection<JsonObject> mongoCollection = getCollection(collection);
    Class<?> resultClass = this.getClass().getClassLoader().loadClass(resultClassname);
    return setDistinctOptions(mongoCollection.distinct(fieldName, bquery, resultClass), distinctOptions);
  }

  private AggregatePublisher<JsonObject> doAggregate(final String collection, final JsonArray pipeline, final AggregateOptions aggregateOptions) {
    requireNonNull(collection, COLLECTION_CANNOT_BE_NULL);
    requireNonNull(pipeline, PIPELINE_CANNOT_BE_NULL);
    requireNonNull(aggregateOptions, "aggregateOptions cannot be null");
    final MongoCollection<JsonObject> coll = getCollection(collection);
    final List<Bson> bpipeline = new ArrayList<>(pipeline.size());
    pipeline.getList().forEach(entry -> bpipeline.add(wrap(JsonObject.mapFrom(entry))));
    AggregatePublisher<JsonObject> aggregate = coll.aggregate(bpipeline, JsonObject.class);

    if(aggregateOptions.getCollation() != null) {
      aggregate.collation(aggregateOptions.getCollation().toMongoDriverObject());
    }
    if (aggregateOptions.getBatchSize() != -1) {
      aggregate.batchSize(aggregateOptions.getBatchSize());
    }
    if (aggregateOptions.getMaxTime() > 0) {
      aggregate.maxTime(aggregateOptions.getMaxTime(), TimeUnit.MILLISECONDS);
    }
    if (aggregateOptions.getAllowDiskUse() != null) {
      aggregate.allowDiskUse(aggregateOptions.getAllowDiskUse());
    }
    return aggregate;
  }

  JsonObject encodeKeyWhenUseObjectId(JsonObject json) {
    if (!useObjectId) return json;

    Object idString = json.getValue(ID_FIELD, null);
    if (idString instanceof String && ObjectId.isValid((String) idString)) {
      json.put(ID_FIELD, new JsonObject().put(JsonObjectCodec.OID_FIELD, idString));
    }

    return json;
  }

  private JsonObject decodeKeyWhenUseObjectId(JsonObject json) {
    if (!useObjectId) return json;

    Object idField = json.getValue(ID_FIELD, null);
    if (!(idField instanceof JsonObject)) return json;

    Object idString = ((JsonObject) idField).getValue(JsonObjectCodec.OID_FIELD, null);
    if (!(idString instanceof String)) return json;

    json.put(ID_FIELD, idString);

    return json;
  }

  private FindPublisher<JsonObject> doFind(String collection, JsonObject query, FindOptions options) {
    MongoCollection<JsonObject> coll = getCollection(collection);
    Bson bquery = wrap(encodeKeyWhenUseObjectId(query));
    FindPublisher<JsonObject> find = coll.find(bquery, JsonObject.class);
    if (options.getLimit() != -1) {
      find.limit(options.getLimit());
    }
    if (options.getSkip() > 0) {
      find.skip(options.getSkip());
    }
    if (options.getSort() != null) {
      find.sort(wrap(options.getSort()));
    }
    if (options.getFields() != null) {
      find.projection(wrap(options.getFields()));
    }
    if (options.getHint() != null) {
      find.hint(wrap(options.getHint()));
    }
    if (options.getHintString() != null && !options.getHintString().isEmpty()) {
      find.hintString(options.getHintString());
    }
    if(options.getCollation() != null) {
      find.collation(options.getCollation().toMongoDriverObject());
    }
    return find;
  }

  private MongoCollection<JsonObject> getCollection(String name) {
    return getCollection(name, null);
  }

  private MongoCollection<JsonObject> getCollection(String name, @Nullable WriteOption writeOption) {
    MongoCollection<JsonObject> coll = holder.db.getCollection(name, JsonObject.class);
    if (coll != null && writeOption != null) {
      coll = coll.withWriteConcern(WriteConcern.valueOf(writeOption.name()));
    }
    return coll;
  }

  private com.mongodb.client.model.IndexOptions mongoIndexOptions(IndexOptions options) {
    CollationOptions co = options.getCollation();
    com.mongodb.client.model.IndexOptions o = new com.mongodb.client.model.IndexOptions()
      .background(options.isBackground())
      .unique(options.isUnique())
      .name(options.getName())
      .sparse(options.isSparse())
      .expireAfter(options.getExpireAfter(TimeUnit.SECONDS), TimeUnit.SECONDS)
      .version(options.getVersion())
      .weights(toBson(options.getWeights()))
      .defaultLanguage(options.getDefaultLanguage())
      .languageOverride(options.getLanguageOverride())
      .textVersion(options.getTextVersion())
      .sphereVersion(options.getSphereVersion())
      .bits(options.getBits())
      .min(options.getMin())
      .max(options.getMax())
      .bucketSize(options.getBucketSize())
      .storageEngine(toBson(options.getStorageEngine()))
      .partialFilterExpression(toBson(options.getPartialFilterExpression()));
      if (co != null) {
        o.collation(co.toMongoDriverObject());
      }
      return o;
  }

  @Nullable JsonObjectBsonAdapter wrap(@Nullable JsonObject jsonObject) {
    return jsonObject == null ? null : new JsonObjectBsonAdapter(jsonObject);
  }

  private void removeFromMap(LocalMap<String, MongoHolder> map, String dataSourceName) {
    synchronized (vertx) {
      map.remove(dataSourceName);
      if (map.isEmpty()) {
        map.close();
      }
    }
  }

  private MongoHolder lookupHolder(String datasourceName, JsonObject config) {
    synchronized (vertx) {
      LocalMap<String, MongoHolder> map = vertx.sharedData().getLocalMap(DS_LOCAL_MAP_NAME);
      MongoHolder theHolder = map.get(datasourceName);
      if (theHolder == null) {
        theHolder = new MongoHolder(config, () -> removeFromMap(map, datasourceName));
        map.put(datasourceName, theHolder);
      } else {
        theHolder.incRefCount();
      }
      return theHolder;
    }
  }

  @Override
  public void close(Handler<AsyncResult<Void>> handler) {
    ContextInternal ctx = vertx.getOrCreateContext();
    close(ctx.promise(handler));
  }

  private class MongoHolder implements Shareable {
    com.mongodb.reactivestreams.client.MongoClient mongo;
    MongoDatabase db;
    JsonObject config;
    Runnable closeRunner;
    int refCount = 1;

    MongoHolder(JsonObject config, Runnable closeRunner) {
      this.config = config;
      this.closeRunner = closeRunner;
    }

    synchronized com.mongodb.reactivestreams.client.MongoClient mongo(Vertx vertx) {
      if (mongo == null) {
        MongoClientOptionsParser parser = new MongoClientOptionsParser(vertx, config);
        mongo = MongoClients.create(parser.settings());
        db = mongo.getDatabase(parser.database());
      }
      return mongo;
    }

    synchronized com.mongodb.reactivestreams.client.MongoClient mongo(Vertx vertx, MongoClientSettings settings) {
      if (mongo == null) {
        MongoClientOptionsParser parser = new MongoClientOptionsParser(vertx, config);
        mongo = MongoClients.create(settings);
        db = mongo.getDatabase(parser.database());
      }
      return mongo;
    }

    synchronized void incRefCount() {
      refCount++;
    }

    void close() {
      java.io.Closeable client;
      Runnable callback;
      synchronized (this) {
        if (--refCount > 0) {
          return;
        }
        client = mongo;
        mongo = null;
        callback = closeRunner;
        closeRunner = null;
      }
      if (callback != null) {
        callback.run();
      }
      if (client != null) {
        MongoClientImpl.this.vertx.executeBlocking(p -> {
          try {
            client.close();
          } catch (IOException e) {
            p.fail(e);
          }
        });
      }
    }
  }
}

/*
 * Copyright 2011 Impetus Infotech.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.impetus.kundera.client;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.impetus.kundera.Client;
import com.impetus.kundera.ejb.EntityManagerImpl;
import com.impetus.kundera.loader.DBType;
import com.impetus.kundera.metadata.EntityMetadata;
import com.impetus.kundera.metadata.EntityMetadata.Column;
import com.impetus.kundera.mongodb.MongoDBDataHandler;
import com.impetus.kundera.proxy.EnhancedEntity;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

/**
 * CLient class for MongoDB database.
 * 
 * @author impetusopensource
 */
public class MongoDBClient implements Client
{

    /** The contact node. */
    private String contactNode;

    /** The default port. */
    private String defaultPort;

    /** The db name. */
    private String dbName;

    /** The is connected. */
    private boolean isConnected;

    /** The em. */
    private EntityManager em;

    /** The mongo. */
    Mongo mongo;

    /** The mongo db. */
    DB mongoDb;

    /** The log. */
    private static Log log = LogFactory.getLog(MongoDBClient.class);

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#writeColumns(java.lang.String,
     * java.lang.String, java.lang.String, java.util.List,
     * com.impetus.kundera.proxy.EnhancedEntity)
     */
    @Override
    @Deprecated
    public void writeColumns(String dbName, String documentName, String key, List<Column> columns, EnhancedEntity e)
            throws Exception
    {
        throw new PersistenceException("Not yet implemented");
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.impetus.kundera.Client#writeColumns(com.impetus.kundera.ejb.
     * EntityManagerImpl, com.impetus.kundera.proxy.EnhancedEntity,
     * com.impetus.kundera.metadata.EntityMetadata)
     */
    @Override
    public void writeColumns(EntityManagerImpl em, EnhancedEntity e, EntityMetadata m) throws Exception
    {
        String dbName = m.getSchema();
        String documentName = m.getTableName();
        String key = e.getId();

        log.debug("Checking whether record already exist for " + dbName + "." + documentName + " for " + key);
        Object entity = loadColumns(em, m.getEntityClazz(), dbName, documentName, key, m);
        if (entity != null)
        {
            log.debug("Updating data into " + dbName + "." + documentName + " for " + key);
            DBCollection dbCollection = mongoDb.getCollection(documentName);

            BasicDBObject searchQuery = new BasicDBObject();
            searchQuery.put(m.getIdColumn().getName(), key);
            BasicDBObject updatedDocument = new MongoDBDataHandler().getDocumentFromEntity(em, m, e);
            dbCollection.update(searchQuery, updatedDocument);

        }
        else
        {
            log.debug("Inserting data into " + dbName + "." + documentName + " for " + key);
            DBCollection dbCollection = mongoDb.getCollection(documentName);

            BasicDBObject document = new MongoDBDataHandler().getDocumentFromEntity(em, m, e);
            dbCollection.insert(document);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.impetus.kundera.Client#loadColumns(com.impetus.kundera.ejb.
     * EntityManagerImpl, java.lang.Class, java.lang.String, java.lang.String,
     * java.lang.String, com.impetus.kundera.metadata.EntityMetadata)
     */
    @Override
    public <E> E loadColumns(EntityManagerImpl em, Class<E> clazz, String dbName, String documentName, String key,
            EntityMetadata m) throws Exception
    {
        log.debug("Fetching data from " + documentName + " for PK " + key);
        DBCollection dbCollection = mongoDb.getCollection(documentName);

        BasicDBObject query = new BasicDBObject();
        query.put(m.getIdColumn().getName(), key);

        DBCursor cursor = dbCollection.find(query);
        DBObject fetchedDocument = null;

        if (cursor.hasNext())
        {
            fetchedDocument = cursor.next();
        }
        else
        {
            return null;
        }

        Object entity = new MongoDBDataHandler().getEntityFromDocument(em, clazz, m, fetchedDocument);

        return (E) entity;
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.impetus.kundera.Client#loadColumns(com.impetus.kundera.ejb.
     * EntityManagerImpl, java.lang.Class, java.lang.String, java.lang.String,
     * com.impetus.kundera.metadata.EntityMetadata, java.lang.String[])
     */
    @Override
    public <E> List<E> loadColumns(EntityManagerImpl em, Class<E> clazz, String dbName, String documentName,
            EntityMetadata m, String... keys) throws Exception
    {
        log.debug("Fetching data from " + documentName + " for Keys " + keys);

        DBCollection dbCollection = mongoDb.getCollection(documentName);

        BasicDBObject query = new BasicDBObject();
        query.put(m.getIdColumn().getName(), new BasicDBObject("$in", keys));

        DBCursor cursor = dbCollection.find(query);

        List entities = new ArrayList<E>();
        while (cursor.hasNext())
        {
            DBObject fetchedDocument = cursor.next();
            Object entity = new MongoDBDataHandler().getEntityFromDocument(em, clazz, m, fetchedDocument);
            entities.add(entity);
        }
        return entities;
    }

    /**
     * Loads columns from multiple rows restricting results to conditions stored
     * in <code>filterClauseQueue</code>.
     * 
     * @param <E>
     *            the element type
     * @param em
     *            the em
     * @param m
     *            the m
     * @param filterClauseQueue
     *            the filter clause queue
     * @return the list
     * @throws Exception
     *             the exception
     */
    public <E> List<E> loadColumns(EntityManagerImpl em, EntityMetadata m, Queue filterClauseQueue) throws Exception
    {
        String documentName = m.getTableName();
        String dbName = m.getSchema();
        Class clazz = m.getEntityClazz();
        log.debug("Fetching data from " + documentName + " for Filter " + filterClauseQueue);

        BasicDBObject query = new MongoDBDataHandler().createMongoDBQuery(filterClauseQueue);

        List entities = new ArrayList<E>();
        DBCollection dbCollection = mongoDb.getCollection(documentName);

        DBCursor cursor = dbCollection.find(query);

        while (cursor.hasNext())
        {
            DBObject fetchedDocument = cursor.next();
            Object entity = new MongoDBDataHandler().getEntityFromDocument(em, clazz, m, fetchedDocument);
            entities.add(entity);
        }
        return entities;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#delete(java.lang.String,
     * java.lang.String, java.lang.String)
     */
    @Override
    public void delete(String idColumnName, String documentName, String rowId) throws Exception
    {
        DBCollection dbCollection = mongoDb.getCollection(documentName);

        // Find the DBObject to remove first
        BasicDBObject query = new BasicDBObject();
        query.put(idColumnName, rowId);

        DBCursor cursor = dbCollection.find(query);
        DBObject documentToRemove = null;

        if (cursor.hasNext())
        {
            documentToRemove = cursor.next();
        }
        else
        {
            throw new PersistenceException("Can't remove Row# " + rowId + " for " + documentName
                    + " because record doesn't exist.");
        }

        dbCollection.remove(documentToRemove);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#connect()
     */
    @Override
    public void connect()
    {
        if (!isConnected)
        {
            log.info(">>> Connecting to MONGODB at " + contactNode + " on port " + defaultPort);
            try
            {
                mongo = new Mongo(contactNode, Integer.parseInt(defaultPort));
                mongoDb = mongo.getDB(dbName);
                isConnected = true;
                log.info("CONNECTED to MONGODB at " + contactNode + " on port " + defaultPort);
            }
            catch (NumberFormatException e)
            {
                log.error("Invalid format for MONGODB port, Unale to connect!" + "; Details:" + e.getMessage());
            }
            catch (UnknownHostException e)
            {
                log.error("Unable to connect to MONGODB at host " + contactNode + "; Details:" + e.getMessage());
            }
            catch (MongoException e)
            {
                log.error("Unable to connect to MONGODB; Details:" + e.getMessage());
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#shutdown()
     */
    @Override
    public void shutdown()
    {
        if (isConnected && mongo != null)
        {
            log.info("Closing connection to MONGODB at " + contactNode + " on port " + defaultPort);
            mongo.close();
        }
        else
        {
            log.warn("Can't close connection to MONGODB, it was already disconnected");
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#getType()
     */
    @Override
    public DBType getType()
    {
        return DBType.MONGODB;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#setContactNodes(java.lang.String[])
     */
    @Override
    public void setContactNodes(String... contactNodes)
    {
        this.contactNode = contactNodes[0];
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#setDefaultPort(int)
     */
    @Override
    public void setDefaultPort(int defaultPort)
    {
        this.defaultPort = String.valueOf(defaultPort);
    }

    // For MongoDB, keyspace means DB name
    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#setKeySpace(java.lang.String)
     */
    @Override
    public void setKeySpace(String keySpace)
    {
        this.dbName = keySpace;
    }

    /**
     * Creates the index.
     * 
     * @param collectionName
     *            the collection name
     * @param columnList
     *            the column list
     * @param order
     *            the order
     */
    public void createIndex(String collectionName, List<String> columnList, int order)
    {
        DBCollection coll = mongoDb.getCollection(collectionName);

        List<DBObject> indexes = coll.getIndexInfo(); // List of all current
        // indexes on collection
        Set<String> indexNames = new HashSet<String>(); // List of all current
        // index names
        for (DBObject index : indexes)
        {
            BasicDBObject obj = (BasicDBObject) index.get("key");
            Set<String> set = obj.keySet(); // Set containing index name which
            // is key
            indexNames.addAll(set);
        }

        // Create index if not already created
        for (String columnName : columnList)
        {
            if (!indexNames.contains(columnName))
            {
                coll.createIndex(new BasicDBObject(columnName, order));
            }
        }
    }
}

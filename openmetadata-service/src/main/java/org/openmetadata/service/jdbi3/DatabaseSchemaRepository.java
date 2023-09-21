/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.jdbi3;

import static org.openmetadata.schema.type.Include.ALL;
import static org.openmetadata.service.resources.EntityResource.searchClient;

import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.entity.data.Database;
import org.openmetadata.schema.entity.data.DatabaseSchema;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.service.Entity;
import org.openmetadata.service.resources.databases.DatabaseSchemaResource;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.FullyQualifiedName;
import org.openmetadata.service.util.JsonUtils;
import org.openmetadata.service.util.RestUtil;

@Slf4j
public class DatabaseSchemaRepository extends EntityRepository<DatabaseSchema> {
  public DatabaseSchemaRepository(CollectionDAO dao) {
    super(
        DatabaseSchemaResource.COLLECTION_PATH,
        Entity.DATABASE_SCHEMA,
        DatabaseSchema.class,
        dao.databaseSchemaDAO(),
        dao,
        "",
        "");
    supportsSearchIndex = true;
  }

  @Override
  public void setFullyQualifiedName(DatabaseSchema schema) {
    schema.setFullyQualifiedName(
        FullyQualifiedName.add(schema.getDatabase().getFullyQualifiedName(), schema.getName()));
  }

  @Override
  public void prepare(DatabaseSchema schema, boolean update) {
    populateDatabase(schema);
  }

  @Override
  public void storeEntity(DatabaseSchema schema, boolean update) {
    // Relationships and fields such as service are derived and not stored as part of json
    EntityReference service = schema.getService();
    schema.withService(null);

    store(schema, update);
    // Restore the relationships
    schema.withService(service);
  }

  @Override
  public void storeRelationships(DatabaseSchema schema) {
    EntityReference database = schema.getDatabase();
    addRelationship(
        database.getId(), schema.getId(), database.getType(), Entity.DATABASE_SCHEMA, Relationship.CONTAINS);
  }

  private List<EntityReference> getTables(DatabaseSchema schema) {
    return schema == null
        ? Collections.emptyList()
        : findTo(schema.getId(), Entity.DATABASE_SCHEMA, Relationship.CONTAINS, Entity.TABLE);
  }

  public DatabaseSchema setFields(DatabaseSchema schema, Fields fields) {
    setDefaultFields(schema);
    schema.setTables(fields.contains("tables") ? getTables(schema) : null);
    return schema.withUsageSummary(
        fields.contains("usageSummary") ? EntityUtil.getLatestUsage(daoCollection.usageDAO(), schema.getId()) : null);
  }

  public DatabaseSchema clearFields(DatabaseSchema schema, Fields fields) {
    schema.setTables(fields.contains("tables") ? schema.getTables() : null);
    return schema.withUsageSummary(fields.contains("usageSummary") ? schema.getUsageSummary() : null);
  }

  private void setDefaultFields(DatabaseSchema schema) {
    EntityReference databaseRef = getContainer(schema.getId());
    Database database = Entity.getEntity(databaseRef, "", Include.ALL);
    schema.withDatabase(databaseRef).withService(database.getService());
  }

  @Override
  public DatabaseSchema setInheritedFields(DatabaseSchema schema, Fields fields) {
    Database database = Entity.getEntity(Entity.DATABASE, schema.getDatabase().getId(), "owner,domain", ALL);
    inheritOwner(schema, fields, database);
    inheritDomain(schema, fields, database);
    schema.withRetentionPeriod(
        schema.getRetentionPeriod() == null ? database.getRetentionPeriod() : schema.getRetentionPeriod());
    return schema;
  }

  @Override
  public void restorePatchAttributes(DatabaseSchema original, DatabaseSchema updated) {
    // Patch can't make changes to following fields. Ignore the changes
    updated
        .withFullyQualifiedName(original.getFullyQualifiedName())
        .withName(original.getName())
        .withService(original.getService())
        .withId(original.getId());
  }

  @Override
  public void postUpdate(DatabaseSchema original, DatabaseSchema updated) {
    if (supportsSearchIndex) {
      if (original.getOwner() == null && updated.getOwner() != null) {
        String scriptTxt = "if(ctx._source.owner == null){ ctx._source.put('owner', params)}";
        searchClient.updateSearchByQuery(
            JsonUtils.deepCopy(updated, DatabaseSchema.class), scriptTxt, "databaseSchema.id", updated.getOwner());
      }
      if (original.getDomain() == null && updated.getDomain() != null) {
        String scriptTxt = "if(ctx._source.domain == null){ ctx._source.put('domain', params)}";
        searchClient.updateSearchByQuery(
            JsonUtils.deepCopy(updated, DatabaseSchema.class), scriptTxt, "databaseSchema.id", updated.getDomain());
      }
      if (original.getOwner() != null && updated.getOwner() == null) {
        String scriptTxt =
            String.format(
                "if(ctx._source.owner.id == '%s'){ ctx._source.remove('owner')}",
                original.getOwner().getId().toString());
        searchClient.updateSearchByQuery(
            JsonUtils.deepCopy(updated, DatabaseSchema.class), scriptTxt, "databaseSchema.id", updated.getOwner());
      }
      if (original.getDomain() != null && updated.getDomain() == null) {
        String scriptTxt =
            String.format(
                "if(ctx._source.domain.id == '%s'){ ctx._source.remove('domain')}",
                original.getDomain().getId().toString());
        ;
        searchClient.updateSearchByQuery(
            JsonUtils.deepCopy(updated, DatabaseSchema.class), scriptTxt, "databaseSchema.id", updated.getDomain());
      }
      String scriptTxt = "for (k in params.keySet()) { ctx._source.put(k, params.get(k)) }";
      searchClient.updateSearchEntityUpdated(JsonUtils.deepCopy(updated, DatabaseSchema.class), scriptTxt, "");
    }
  }

  @Override
  public void deleteFromSearch(DatabaseSchema entity, String changeType) {
    if (supportsSearchIndex) {
      if (changeType.equals(RestUtil.ENTITY_SOFT_DELETED) || changeType.equals(RestUtil.ENTITY_RESTORED)) {
        searchClient.softDeleteOrRestoreEntityFromSearch(
            JsonUtils.deepCopy(entity, DatabaseSchema.class),
            changeType.equals(RestUtil.ENTITY_SOFT_DELETED),
            "databaseSchema.id");
      } else {
        searchClient.updateSearchEntityDeleted(
            JsonUtils.deepCopy(entity, DatabaseSchema.class), "", "databaseSchema.id");
      }
    }
  }

  @Override
  public void restoreFromSearch(DatabaseSchema entity) {
    if (supportsSearchIndex) {
      searchClient.softDeleteOrRestoreEntityFromSearch(
          JsonUtils.deepCopy(entity, DatabaseSchema.class), false, "databaseSchema.fullyQualifiedName");
    }
  }

  @Override
  public EntityRepository<DatabaseSchema>.EntityUpdater getUpdater(
      DatabaseSchema original, DatabaseSchema updated, Operation operation) {
    return new DatabaseSchemaUpdater(original, updated, operation);
  }

  private void populateDatabase(DatabaseSchema schema) {
    Database database = Entity.getEntity(schema.getDatabase(), "", ALL);
    schema
        .withDatabase(database.getEntityReference())
        .withService(database.getService())
        .withServiceType(database.getServiceType());
  }

  public class DatabaseSchemaUpdater extends EntityUpdater {
    public DatabaseSchemaUpdater(DatabaseSchema original, DatabaseSchema updated, Operation operation) {
      super(original, updated, operation);
    }

    @Override
    public void entitySpecificUpdate() {
      recordChange("retentionPeriod", original.getRetentionPeriod(), updated.getRetentionPeriod());
      recordChange("sourceUrl", original.getSourceUrl(), updated.getSourceUrl());
    }
  }
}

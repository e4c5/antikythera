package sa.com.cloudsolutions.antikythera.parser.converter;

import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;
import com.raditha.hql.converter.JoinMapping;

import java.util.Map;

/**
 * Metadata about an entity, including its table mapping and relationships.
 *
 * @param entity the TypeWrapper representing the entity class
 * @param tableName the name of the database table
 * @param propertyToColumnMap a map of property names to column names
 * @param relationshipMap a map of property names to JoinMapping for relationships
 */
public record EntityMetadata(TypeWrapper entity, String tableName,
                             Map<String, String> propertyToColumnMap,
                             Map<String, JoinMapping> relationshipMap) {
}
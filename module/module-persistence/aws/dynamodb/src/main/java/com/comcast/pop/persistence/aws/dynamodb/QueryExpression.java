package com.comcast.pop.persistence.aws.dynamodb;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.Select;
import com.comcast.pop.persistence.api.field.CountField;
import com.comcast.pop.persistence.api.field.FieldsField;
import com.comcast.pop.persistence.api.field.LimitField;
import com.comcast.pop.persistence.api.query.Query;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Creates the necessary DynamoDB Query and Scan expressions from our ByQuery objects.
 * DynamoDB requires you have a primary or index (partition + optional sort) key for queries. This class requires you specify
 * the table indexes.
 * Example:
 * The following would query by primary key 'id' then filter the results by title before returning the results.
 * byId() and byTitle()
 * keyExpression : id, filterExpression : title
 *
 * If linkId is associated to an index we do the same type of query but with the index specified.
 * byLinkId() and byTitle()
 * keyExpression : linkId, filterExpression : title
 * indexName = link_index
 *
 * If linkId (partition key) and customerId (sort key) is associated to an index we do the same type of query but with the index specified.
 * byLinkId(), byCustomerId() byTitle()
 * keyExpression : linkId and customerId, filterExpression : title
 * indexName = link_index (assuming the table index has the partition and sort key specified)
 *
 * Scan is also supported and should only be used in cases where a full table scan is necessary, ie: getAll()
 * @param <T>
 */
public class QueryExpression<T>
{
    protected static Logger logger = LoggerFactory.getLogger(QueryExpression.class);
    private static final String KEY_CONDITION = "%s = %s";
    private static final String QUERY_VALUE = ":%s";
    private static final String AND_STATEMENT = " AND ";
    private static final LimitField limitField = new LimitField();
    private static final FieldsField fieldsField = new FieldsField();
    private static final CountField countField = new CountField();
    private TableIndexes tableIndexes;
    private TableIndex queryIndex;
    private List<Query> primaryKeyQueries;
    private Query limitQuery;
    private Select selectQuery;
    private List<Query> filterQueries = new ArrayList<>();
    private String filterAttributes;

    public QueryExpression(TableIndexes tableIndexes, List<Query> queries)
    {
        this.tableIndexes = tableIndexes == null ? new TableIndexes() : tableIndexes;
        primaryKeyQueries = new LinkedList<>();
        analyzeQueryTypes(queries);
    }

    public DynamoDBQueryExpression<T> forQuery()
    {
        if(primaryKeyQueries.size() == 0)
        {
            logger.warn("No queries found with primary key field.");
            return null;
        }

        Map<String, AttributeValue> awsQueryValueMap = new HashMap<>();
        DynamoDBQueryExpression<T> expression = new DynamoDBQueryExpression<>();

        // add in the primary key queries (may optionally include the sort key)
        expression.withKeyConditionExpression(String.join(AND_STATEMENT, generateConditions(primaryKeyQueries, awsQueryValueMap)))
            .withExpressionAttributeValues(awsQueryValueMap)
            .withConsistentRead(false);

        if(filterAttributes != null)
        {
            expression.withProjectionExpression(filterAttributes);
        }
        if(queryIndex != null)
        {
            expression.withIndexName(queryIndex.getName());
        }
        if(filterQueries != null && filterQueries.size() > 0)
        {
            expression.withFilterExpression(String.join(AND_STATEMENT, generateConditions(filterQueries, awsQueryValueMap)));
        }
        /* // item limits are meaningless to DynamoDB expressions (at least in the current context)
        if (limitQuery != null){}
        */
        if(selectQuery != null)
        {
            expression.withSelect(selectQuery);
        }

        logger.info("DynamoDB query with key condition={}, value map={}, limit={}, fields={}", expression.getKeyConditionExpression(), awsQueryValueMap.toString(),
            expression.getLimit(), expression.getProjectionExpression());

        return expression;
    }

    /**
     * Where no primary key is available.
     * @return DynanoDBScanExpression The scan expression dynamodb needs for scanning
     */
    public DynamoDBScanExpression forScan()
    {
        DynamoDBScanExpression expression = new DynamoDBScanExpression();
        for(Query query : filterQueries)
        {
            Condition condition = new Condition()
                .withComparisonOperator(ComparisonOperator.EQ)
                .withAttributeValueList(new AttributeValue().withS(query.getValue().toString()));
            expression.addFilterCondition(query.getField().name(), condition);
        }
        if(filterAttributes != null)
        {
            expression.withProjectionExpression(filterAttributes);
        }
        final String queries = filterQueries.stream().map(query -> query.getField().name() + " == " + query.getValue())
            .collect(Collectors.joining(","));
        logger.info("DynamoDB scan with queries={}, limit={}, fields={}", queries, expression.getLimit(), expression.getProjectionExpression());
        return expression;
    }

    public boolean hasKey()
    {
        return primaryKeyQueries.size() > 0;
    }
    public boolean hasCount()
    {
        return this.selectQuery != null && selectQuery == Select.COUNT;
    }
    public Integer getLimit() { return limitQuery != null && StringUtils.isNotBlank(limitQuery.getStringValue()) ? limitQuery.getIntValue() : null; }
    private List<String> generateConditions(List<Query> queries, Map<String, AttributeValue> valueMap)
    {
        List<String> conditions = new LinkedList<>();
        queries.forEach(query ->
        {
            final String awsQueryValueKey = String.format(QUERY_VALUE, query.getField().name());
            valueMap.put(awsQueryValueKey, new AttributeValue().withS(query.getValue().toString()));
            conditions.add(String.format(KEY_CONDITION, query.getField().name(), awsQueryValueKey));
        });
        return conditions;
    }

    /**
     * The Query objects are separated by primary/index, limit, or filter.
     * @param queries byQueries to query upon
     */
    private void analyzeQueryTypes(List<Query> queries)
    {
        if(queries == null || queries.size() == 0) return;

        // look for the best fit index based on all the fields being queried
        queryIndex = tableIndexes.getBestTableIndexMatch(queries.stream().map(q -> q.getField().name()).collect(Collectors.toList()));

        for(Query query : queries)
        {
            String queryFieldName = query.getField().name();
            //The first query that has an index is the index we use, the rest are filters off the data coming back.
            if (limitField.isMatch((queryFieldName)))
            {
                limitQuery = query;
            }
            else if(fieldsField.isMatch(queryFieldName))
            {
                filterAttributes = StringUtils.isNotEmpty(query.getStringValue()) ? query.getStringValue() : null;
            }
            else if(countField.isMatch(queryFieldName))
            {
                selectQuery = Select.COUNT;
            }
            else
            {
                if(queryIndex == null)
                {
                    if(tableIndexes.isPrimary(queryFieldName))
                    {
                        primaryKeyQueries.add(query);
                    }
                    else
                    {
                        filterQueries.add(query);
                    }
                }
                else
                {
                    if(queryIndex.isPrimaryKey(queryFieldName))
                    {
                        primaryKeyQueries.add(query);
                    }
                    else
                    {
                        filterQueries.add(query);
                    }
                }
            }
        }
    }

}

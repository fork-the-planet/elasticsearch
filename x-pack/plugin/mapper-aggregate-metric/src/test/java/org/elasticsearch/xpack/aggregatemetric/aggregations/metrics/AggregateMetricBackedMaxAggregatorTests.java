/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.aggregatemetric.aggregations.metrics;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregatorTestCase;
import org.elasticsearch.search.aggregations.metrics.Max;
import org.elasticsearch.search.aggregations.metrics.MaxAggregationBuilder;
import org.elasticsearch.search.aggregations.support.AggregationInspectionHelper;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.aggregations.support.ValuesSourceType;
import org.elasticsearch.xpack.aggregatemetric.AggregateMetricMapperPlugin;
import org.elasticsearch.xpack.aggregatemetric.aggregations.support.AggregateMetricsValuesSourceType;
import org.elasticsearch.xpack.aggregatemetric.mapper.AggregateMetricDoubleFieldMapper.AggregateMetricDoubleFieldType;
import org.elasticsearch.xpack.aggregatemetric.mapper.AggregateMetricDoubleFieldMapper.Metric;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static java.util.Collections.singleton;
import static org.elasticsearch.xpack.aggregatemetric.mapper.AggregateMetricDoubleFieldMapper.subfieldName;

public class AggregateMetricBackedMaxAggregatorTests extends AggregatorTestCase {

    private static final String FIELD_NAME = "aggregate_metric_field";

    public void testMatchesNumericDocValues() throws IOException {
        testCase(new MatchAllDocsQuery(), iw -> {
            iw.addDocument(
                List.of(
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.max), Double.doubleToLongBits(10)),
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.min), Double.doubleToLongBits(2))
                )
            );

            iw.addDocument(
                List.of(
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.max), Double.doubleToLongBits(50)),
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.min), Double.doubleToLongBits(5))
                )
            );
        }, max -> {
            assertEquals(50, max.value(), 0d);
            assertTrue(AggregationInspectionHelper.hasValue(max));
        });
    }

    public void testNoDocs() throws IOException {
        testCase(new MatchAllDocsQuery(), iw -> {
            // Intentionally not writing any docs
        }, max -> {
            assertEquals(Double.NEGATIVE_INFINITY, max.value(), 0d);
            assertFalse(AggregationInspectionHelper.hasValue(max));
        });
    }

    public void testNoMatchingField() throws IOException {
        testCase(new MatchAllDocsQuery(), iw -> {
            iw.addDocument(singleton(new NumericDocValuesField("wrong_number", 7)));
            iw.addDocument(singleton(new NumericDocValuesField("wrong_number", 1)));
        }, max -> {
            assertEquals(Double.NEGATIVE_INFINITY, max.value(), 0d);
            assertFalse(AggregationInspectionHelper.hasValue(max));
        });
    }

    public void testQueryFiltering() throws IOException {
        testCase(new TermQuery(new Term("match", "yes")), iw -> {
            iw.addDocument(
                List.of(
                    new StringField("match", "yes", Field.Store.NO),
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.max), Double.doubleToLongBits(10)),
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.min), Double.doubleToLongBits(2))
                )
            );
            iw.addDocument(
                List.of(
                    new StringField("match", "yes", Field.Store.NO),
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.max), Double.doubleToLongBits(20)),
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.min), Double.doubleToLongBits(5))
                )
            );
            iw.addDocument(
                List.of(
                    new StringField("match", "no", Field.Store.NO),
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.max), Double.doubleToLongBits(40)),
                    new NumericDocValuesField(subfieldName(FIELD_NAME, Metric.min), Double.doubleToLongBits(1))
                )
            );
        }, max -> {
            assertEquals(20L, max.value(), 0d);
            assertTrue(AggregationInspectionHelper.hasValue(max));
        });
    }

    /**
     * Create a default aggregate_metric_double field type containing min and a max metrics.
     *
     * @param fieldName the name of the field
     * @return the created field type
     */
    private AggregateMetricDoubleFieldType createDefaultFieldType(String fieldName) {
        AggregateMetricDoubleFieldType fieldType = new AggregateMetricDoubleFieldType(fieldName);

        for (Metric m : List.of(Metric.min, Metric.max)) {
            String subfieldName = subfieldName(fieldName, m);
            NumberFieldMapper.NumberFieldType subfield = new NumberFieldMapper.NumberFieldType(
                subfieldName,
                NumberFieldMapper.NumberType.DOUBLE
            );
            fieldType.addMetricField(m, subfield);
        }
        fieldType.setDefaultMetric(Metric.min);
        return fieldType;
    }

    private void testCase(Query query, CheckedConsumer<RandomIndexWriter, IOException> buildIndex, Consumer<Max> verify)
        throws IOException {
        MappedFieldType fieldType = createDefaultFieldType(FIELD_NAME);
        AggregationBuilder aggregationBuilder = createAggBuilderForTypeTest(fieldType, FIELD_NAME);
        testCase(buildIndex, verify, new AggTestConfig(aggregationBuilder, fieldType).withQuery(query));
    }

    @Override
    protected List<SearchPlugin> getSearchPlugins() {
        return List.of(new AggregateMetricMapperPlugin());
    }

    @Override
    protected AggregationBuilder createAggBuilderForTypeTest(MappedFieldType fieldType, String fieldName) {
        return new MaxAggregationBuilder("max_agg").field(fieldName);
    }

    @Override
    protected List<ValuesSourceType> getSupportedValuesSourceTypes() {
        return List.of(
            CoreValuesSourceType.NUMERIC,
            CoreValuesSourceType.DATE,
            CoreValuesSourceType.BOOLEAN,
            AggregateMetricsValuesSourceType.AGGREGATE_METRIC
        );
    }

}

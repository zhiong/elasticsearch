/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.querydsl.container;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.sql.SqlIllegalArgumentException;
import org.elasticsearch.xpack.sql.execution.search.FieldExtraction;
import org.elasticsearch.xpack.sql.execution.search.SourceGenerator;
import org.elasticsearch.xpack.sql.expression.Attribute;
import org.elasticsearch.xpack.sql.expression.FieldAttribute;
import org.elasticsearch.xpack.sql.expression.LiteralAttribute;
import org.elasticsearch.xpack.sql.expression.function.ScoreAttribute;
import org.elasticsearch.xpack.sql.expression.function.scalar.ScalarFunctionAttribute;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.definition.ProcessorDefinition;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.definition.ScoreProcessorDefinition;
import org.elasticsearch.xpack.sql.querydsl.agg.AggPath;
import org.elasticsearch.xpack.sql.querydsl.agg.Aggs;
import org.elasticsearch.xpack.sql.querydsl.agg.GroupingAgg;
import org.elasticsearch.xpack.sql.querydsl.agg.LeafAgg;
import org.elasticsearch.xpack.sql.querydsl.query.BoolQuery;
import org.elasticsearch.xpack.sql.querydsl.query.MatchAll;
import org.elasticsearch.xpack.sql.querydsl.query.NestedQuery;
import org.elasticsearch.xpack.sql.querydsl.query.Query;
import org.elasticsearch.xpack.sql.tree.Location;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.xpack.sql.util.CollectionUtils.combine;

public class QueryContainer {

    private final Aggs aggs;
    private final Query query;

    // final output seen by the client (hence the list or ordering)
    // gets converted by the Scroller into Extractors for hits or actual results in case of aggregations
    private final List<FieldExtraction> columns;

    // aliases (maps an alias to its actual resolved attribute)
    private final Map<Attribute, Attribute> aliases;

    // pseudo functions (like count) - that are 'extracted' from other aggs
    private final Map<String, GroupingAgg> pseudoFunctions;

    // scalar function processors - recorded as functions get folded;
    // at scrolling, their inputs (leaves) get updated
    private final Map<Attribute, ProcessorDefinition> scalarFunctions;

    private final Set<Sort> sort;
    private final int limit;

    // computed
    private final boolean aggsOnly;
    private final int aggDepth;

    public QueryContainer() {
        this(null, null, null, null, null, null, null, -1);
    }

    public QueryContainer(Query query, Aggs aggs, List<FieldExtraction> refs, Map<Attribute, Attribute> aliases,
            Map<String, GroupingAgg> pseudoFunctions,
            Map<Attribute, ProcessorDefinition> scalarFunctions,
            Set<Sort> sort, int limit) {
        this.query = query;
        this.aggs = aggs == null ? new Aggs() : aggs;
        this.aliases = aliases == null || aliases.isEmpty() ? emptyMap() : aliases;
        this.pseudoFunctions = pseudoFunctions == null || pseudoFunctions.isEmpty() ? emptyMap() : pseudoFunctions;
        this.scalarFunctions = scalarFunctions == null || scalarFunctions.isEmpty() ? emptyMap() : scalarFunctions;
        this.columns = refs == null || refs.isEmpty() ? emptyList() : refs;
        this.sort = sort == null || sort.isEmpty() ? emptySet() : sort;
        this.limit = limit;
        aggsOnly = columns.stream().allMatch(FieldExtraction::supportedByAggsOnlyQuery);
        aggDepth = columns.stream().mapToInt(FieldExtraction::depth).max().orElse(0);
    }

    public Query query() {
        return query;
    }

    public Aggs aggs() {
        return aggs;
    }

    public List<FieldExtraction> columns() {
        return columns;
    }

    public Map<Attribute, Attribute> aliases() {
        return aliases;
    }

    public Map<String, GroupingAgg> pseudoFunctions() {
        return pseudoFunctions;
    }

    public Set<Sort> sort() {
        return sort;
    }

    public int limit() {
        return limit;
    }

    public boolean isAggsOnly() {
        return aggsOnly;
    }

    public int aggDepth() {
        return aggDepth;
    }

    public boolean hasColumns() {
        return !columns.isEmpty();
    }

    //
    // copy methods
    //

    public QueryContainer with(Query q) {
        return new QueryContainer(q, aggs, columns, aliases, pseudoFunctions, scalarFunctions, sort, limit);
    }

    public QueryContainer with(List<FieldExtraction> r) {
        return new QueryContainer(query, aggs, r, aliases, pseudoFunctions, scalarFunctions, sort, limit);
    }

    public QueryContainer withAliases(Map<Attribute, Attribute> a) {
        return new QueryContainer(query, aggs, columns, a, pseudoFunctions, scalarFunctions, sort, limit);
    }

    public QueryContainer withPseudoFunctions(Map<String, GroupingAgg> p) {
        return new QueryContainer(query, aggs, columns, aliases, p, scalarFunctions, sort, limit);
    }

    public QueryContainer with(Aggs a) {
        return new QueryContainer(query, a, columns, aliases, pseudoFunctions, scalarFunctions, sort, limit);
    }

    public QueryContainer withLimit(int l) {
        return l == limit ? this : new QueryContainer(query, aggs, columns, aliases, pseudoFunctions, scalarFunctions, sort, l);
    }

    public QueryContainer withScalarProcessors(Map<Attribute, ProcessorDefinition> procs) {
        return new QueryContainer(query, aggs, columns, aliases, pseudoFunctions, procs, sort, limit);
    }

    public QueryContainer sort(Sort sortable) {
        Set<Sort> sort = new LinkedHashSet<>(this.sort);
        sort.add(sortable);
        return new QueryContainer(query, aggs, columns, aliases, pseudoFunctions, scalarFunctions, sort, limit);
    }

    private String aliasName(Attribute attr) {
        return aliases.getOrDefault(attr, attr).name();
    }

    //
    // reference methods
    //
    private FieldExtraction topHitFieldRef(FieldAttribute fieldAttr) {
        return new SearchHitFieldRef(aliasName(fieldAttr), fieldAttr.field().hasDocValues());
    }

    private Tuple<QueryContainer, FieldExtraction> nestedHitFieldRef(FieldAttribute attr) {
        // Find the nested query for this field. If there isn't one then create it
        List<FieldExtraction> nestedRefs = new ArrayList<>();

        String name = aliasName(attr);
        Query q = rewriteToContainNestedField(query, attr.location(),
                attr.nestedParent().name(), name, attr.field().hasDocValues());

        SearchHitFieldRef nestedFieldRef = new SearchHitFieldRef(name, attr.field().hasDocValues(), attr.parent().name());
        nestedRefs.add(nestedFieldRef);

        return new Tuple<>(new QueryContainer(q, aggs, columns, aliases, pseudoFunctions, scalarFunctions, sort, limit), nestedFieldRef);
    }

    static Query rewriteToContainNestedField(@Nullable Query query, Location location, String path, String name, boolean hasDocValues) {
        if (query == null) {
            /* There is no query so we must add the nested query
             * ourselves to fetch the field. */
            return new NestedQuery(location, path, singletonMap(name, hasDocValues), new MatchAll(location));
        }
        if (query.containsNestedField(path, name)) {
            // The query already has the nested field. Nothing to do.
            return query;
        }
        /* The query doesn't have the nested field so we have to ask
         * it to add it. */
        Query rewritten = query.addNestedField(path, name, hasDocValues);
        if (rewritten != query) {
            /* It successfully added it so we can use the rewritten
             * query. */
            return rewritten;
        }
        /* There is no nested query with a matching path so we must
         * add the nested query ourselves just to fetch the field. */
        NestedQuery nested = new NestedQuery(location, path, singletonMap(name, hasDocValues), new MatchAll(location));
        return new BoolQuery(location, true, query, nested);
    }

    // replace function's input with references
    private Tuple<QueryContainer, FieldExtraction> computingRef(ScalarFunctionAttribute sfa) {
        Attribute name = aliases.getOrDefault(sfa, sfa);
        ProcessorDefinition proc = scalarFunctions.get(name);

        // check the attribute itself
        if (proc == null) {
            if (name instanceof ScalarFunctionAttribute) {
                sfa = (ScalarFunctionAttribute) name;
            }
            proc = sfa.processorDef();
        }

        // find the processor inputs (Attributes) and convert them into references
        // no need to promote them to the top since the container doesn't have to be aware
        class QueryAttributeResolver implements ProcessorDefinition.AttributeResolver {
            private QueryContainer container;

            private QueryAttributeResolver(QueryContainer container) {
                this.container = container;
            }

            @Override
            public FieldExtraction resolve(Attribute attribute) {
                Attribute attr = aliases.getOrDefault(attribute, attribute);
                Tuple<QueryContainer, FieldExtraction> ref = container.toReference(attr);
                container = ref.v1();
                return ref.v2();
            }
        }
        QueryAttributeResolver resolver = new QueryAttributeResolver(this);
        proc = proc.resolveAttributes(resolver);
        QueryContainer qContainer = resolver.container;

        // update proc
        Map<Attribute, ProcessorDefinition> procs = new LinkedHashMap<>(qContainer.scalarFunctions());
        procs.put(name, proc);
        qContainer = qContainer.withScalarProcessors(procs);
        return new Tuple<>(qContainer, new ComputedRef(proc));
    }

    public QueryContainer addColumn(Attribute attr) {
        Tuple<QueryContainer, FieldExtraction> tuple = toReference(attr);
        return tuple.v1().addColumn(tuple.v2());
    }

    private Tuple<QueryContainer, FieldExtraction> toReference(Attribute attr) {
        if (attr instanceof FieldAttribute) {
            FieldAttribute fa = (FieldAttribute) attr;
            if (fa.isNested()) {
                return nestedHitFieldRef(fa);
            } else {
                return new Tuple<>(this, topHitFieldRef(fa));
            }
        }
        if (attr instanceof ScalarFunctionAttribute) {
            return computingRef((ScalarFunctionAttribute) attr);
        }
        if (attr instanceof LiteralAttribute) {
            return new Tuple<>(this, new ComputedRef(((LiteralAttribute) attr).asProcessorDefinition()));
        }
        if (attr instanceof ScoreAttribute) {
            return new Tuple<>(this, new ComputedRef(new ScoreProcessorDefinition(attr.location(), attr)));
        }

        throw new SqlIllegalArgumentException("Unknown output attribute {}", attr);
    }

    public QueryContainer addColumn(FieldExtraction ref) {
        return with(combine(columns, ref));
    }

    public Map<Attribute, ProcessorDefinition> scalarFunctions() {
        return scalarFunctions;
    }

    //
    // agg methods
    //
    public QueryContainer addAggColumn(String aggPath) {
        return with(combine(columns, new AggRef(aggPath)));
    }

    public QueryContainer addAggColumn(String aggPath, String innerKey) {
        return with(combine(columns, new AggRef(aggPath, innerKey)));
    }

    public QueryContainer addAggCount(GroupingAgg parentGroup, String functionId) {
        FieldExtraction ref = parentGroup == null ? TotalCountRef.INSTANCE : new AggRef(AggPath.bucketCount(parentGroup.asParentPath()));
        Map<String, GroupingAgg> pseudoFunctions = new LinkedHashMap<>(this.pseudoFunctions);
        pseudoFunctions.put(functionId, parentGroup);
        return new QueryContainer(query, aggs, combine(columns, ref), aliases, pseudoFunctions, scalarFunctions, sort, limit);
    }

    public QueryContainer addAgg(String groupId, LeafAgg agg) {
        return addAgg(groupId, agg, agg.propertyPath());
    }

    public QueryContainer addAgg(String groupId, LeafAgg agg, String aggRefPath) {
        return new QueryContainer(query, aggs.addAgg(groupId, agg), columns, aliases, pseudoFunctions, scalarFunctions, sort, limit);
    }

    public QueryContainer addGroups(Collection<GroupingAgg> values) {
        return with(aggs.addGroups(values));
    }

    public QueryContainer updateGroup(GroupingAgg group) {
        return with(aggs.updateGroup(group));
    }

    public GroupingAgg findGroupForAgg(String aggId) {
        return aggs.findGroupForAgg(aggId);
    }

    //
    // boiler plate
    //

    @Override
    public int hashCode() {
        return Objects.hash(query, aggs, columns, aliases);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        QueryContainer other = (QueryContainer) obj;
        return Objects.equals(query, other.query)
                && Objects.equals(aggs, other.aggs)
                && Objects.equals(columns, other.columns)
                && Objects.equals(aliases, other.aliases)
                && Objects.equals(sort, other.sort)
                && Objects.equals(limit, other.limit);
    }

    @Override
    public String toString() {
        try (XContentBuilder builder = JsonXContent.contentBuilder()) {
            builder.humanReadable(true).prettyPrint();
            SourceGenerator.sourceBuilder(this, null, null).toXContent(builder, ToXContent.EMPTY_PARAMS);
            return Strings.toString(builder);
        } catch (IOException e) {
            throw new RuntimeException("error rendering", e);
        }
    }
}

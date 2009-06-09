/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.query.jdoql;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;

import com.mysema.query.Projectable;
import com.mysema.query.QueryModifiers;
import com.mysema.query.SearchResults;
import com.mysema.query.support.QueryBaseWithProjection;
import com.mysema.query.types.Order;
import com.mysema.query.types.OrderSpecifier;
import com.mysema.query.types.expr.EBoolean;
import com.mysema.query.types.expr.EConstructor;
import com.mysema.query.types.expr.Expr;
import com.mysema.query.types.path.PEntity;

/**
 * Abstract base class for custom implementations of the JDOQLQuery interface.
 * 
 * @author tiwe
 *
 * @param <SubType>
 */
public abstract class AbstractJDOQLQuery<SubType extends AbstractJDOQLQuery<SubType>> extends QueryBaseWithProjection<Object, SubType> implements Projectable {

    private Map<Object,String> constants = new HashMap<Object,String>();
    
    private List<Object> orderedConstants = new ArrayList<Object>();

    private List<Query> queries = new ArrayList<Query>(2);
    
    private List<PEntity<?>> sources = new ArrayList<PEntity<?>>();

    private String filter;

    private final JDOQLPatterns patterns;

    private final PersistenceManager pm;
    
    public AbstractJDOQLQuery(PersistenceManager pm) {
        this(pm, JDOQLPatterns.DEFAULT);
    }

    public AbstractJDOQLQuery(PersistenceManager pm, JDOQLPatterns patterns) {
        this.patterns = patterns;
        this.pm = pm;
    }

    @Override
    protected SubType addToProjection(Expr<?>... o) {
        for (Expr<?> expr : o) {
            if (expr instanceof EConstructor) {
                EConstructor<?> constructor = (EConstructor<?>) expr;
                for (Expr<?> arg : constructor.getArgs()) {
                    super.addToProjection(arg);
                }
            } else {
                super.addToProjection(expr);
            }
        }
        return _this;
    }

    public SubType from(PEntity<?>... o) {
        for (PEntity<?> expr : o) {
            sources.add(expr);
        }
        return _this;
    }

    private String buildFilterString(boolean forCountRow) {
        if (getMetadata().getWhere() == null) {
            constants = new HashMap<Object,String>();
            return null;
        }else{
            JDOQLSerializer serializer = new JDOQLSerializer(patterns, sources.get(0));
            serializer.handle(getMetadata().getWhere());
            constants = serializer.getConstantToLabel();
            return serializer.toString();    
        }        
    }

    @Override
    protected void clear() {
        super.clear();
        filter = null;
    }

    public long count() {
        String filterString = getFilterString();
        Query query = createQuery(filterString, null, true);
        query.setUnique(true);
        if (getMetadata().isDistinct()){
            query.setResult("distinct count(this)");
        }else{
            query.setResult("count(this)");    
        }        
        return (Long) execute(query);
    }

    private Query createQuery(String filterString, QueryModifiers modifiers, boolean forCount) {        
        Query query = pm.newQuery(sources.get(0).getType());        
        queries.add(query);
        if (filterString != null) {
            query.setFilter(filterString);
        }

        // variables
        if (sources.size() > 1) {
            StringBuffer buffer = new StringBuffer();
            for (int i = 1; i < sources.size(); i++) {
                if (buffer.length() > 0) {
                    buffer.append(", ");
                }
                PEntity<?> source = sources.get(i);
                buffer.append(source.getType().getName()).append(" ").append(source.toString());
            }
            query.declareVariables(buffer.toString());
        }
        
        if (!getMetadata().getGroupBy().isEmpty()){
            List<? extends Expr<?>> groupBy = getMetadata().getGroupBy();
            JDOQLSerializer serializer = new JDOQLSerializer(patterns, sources.get(0));
            serializer.setConstantPrefix("varg");
            serializer.handle(", ", groupBy);            
            if (getMetadata().getHaving() != null){
                EBoolean having = getMetadata().getHaving();
                serializer.append(" having ").handle(having);
            }
            query.setGrouping(serializer.toString());
            constants.putAll(serializer.getConstantToLabel());
        }

        // range (not for count)
        if (modifiers != null && modifiers.isRestricting() && !forCount) {
            long fromIncl = 0;
            long toExcl = Long.MAX_VALUE;
            if (modifiers.getOffset() != null) {
                fromIncl = modifiers.getOffset().longValue();
            }
            if (modifiers.getLimit() != null) {
                toExcl = fromIncl + modifiers.getLimit().longValue();
            }
            query.setRange(fromIncl, toExcl);
        }

        // projection (not for count)
        if (!getMetadata().getProjection().isEmpty() && !forCount) {
            List<? extends Expr<?>> projection = getMetadata().getProjection();
            if (projection.size() > 1 || !projection.get(0).equals(sources.get(0))) {
                JDOQLSerializer serializer = new JDOQLSerializer(patterns, sources.get(0));
                serializer.setConstantPrefix("varp");
                if (getMetadata().isDistinct()){
                    serializer.append("distinct ");
                }
                serializer.handle(", ", projection);
                query.setResult(serializer.toString());
                constants.putAll(serializer.getConstantToLabel());
            }else if (getMetadata().isDistinct()){
                query.setResult("distinct this");
            }
        }
        
        // order (not for count)
        if (!getMetadata().getOrderBy().isEmpty() && !forCount) {
            List<OrderSpecifier<?>> order = getMetadata().getOrderBy();
            // TODO : extract constants
            JDOQLSerializer serializer = new JDOQLSerializer(patterns, sources.get(0));
            boolean first = true;
            for (OrderSpecifier<?> os : order) {
                if (!first) {
                    serializer.append(", ");
                }
                serializer.handle(os.getTarget());
                serializer.append(os.getOrder() == Order.ASC ? " ascending" : "descending");
                first = false;
            }
            query.setOrdering(serializer.toString());
        }

        // explicit parameters
        if (!constants.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<Object,String> entry : constants.entrySet()){
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                String key = entry.getValue();
                Object val = entry.getKey();
                orderedConstants.add(val);
                builder.append(val.getClass().getName()).append(" ").append(key);
            }
            query.declareParameters(builder.toString());
        }
        
        return query;
    }

    public Iterator<Object[]> iterate(Expr<?> e1, Expr<?> e2, Expr<?>... rest) {
        // TODO : optimize
        return list(e1, e2, rest).iterator();
    }

    public <RT> Iterator<RT> iterate(Expr<RT> projection) {
        // TODO : optimize
        return list(projection).iterator();
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> list(Expr<?> expr1, Expr<?> expr2, Expr<?>... rest) {
        addToProjection(expr1, expr2);
        addToProjection(rest);
        String filterString = getFilterString();
        return (List<Object[]>) execute(createQuery(filterString, getMetadata().getModifiers(), false));
    }

    private Object execute(Query query) {
        Object rv;
        if (!orderedConstants.isEmpty()) {
            rv = query.executeWithArray(orderedConstants.toArray());
        } else {
            rv = query.execute();
        }
        // query.closeAll();
        return rv;
    }

    @SuppressWarnings("unchecked")
    public <RT> List<RT> list(Expr<RT> expr) {
        addToProjection(expr);
        String filterString = getFilterString();
        return (List<RT>) execute(createQuery(filterString, getMetadata().getModifiers(), false));
    }

    @SuppressWarnings("unchecked")
    public <RT> SearchResults<RT> listResults(Expr<RT> expr) {
        addToProjection(expr);
        Query countQuery = createQuery(getFilterString(), null, true);
        countQuery.setUnique(true);
        countQuery.setResult("count(this)");
        long total = (Long) execute(countQuery);
        if (total > 0) {
            QueryModifiers modifiers = getMetadata().getModifiers();
            String filterString = getFilterString();
            Query query = createQuery(filterString, modifiers, false);
            return new SearchResults<RT>((List<RT>) execute(query), modifiers,
                    total);
        } else {
            return SearchResults.emptyResults();
        }
    }

    private String getFilterString() {
        if (filter == null) {
            filter = buildFilterString(false);
        }
        return filter;
    }

    @SuppressWarnings("unchecked")
    public <RT> RT uniqueResult(Expr<RT> expr) {
        addToProjection(expr);
        String filterString = getFilterString();
        Query query = createQuery(filterString, QueryModifiers.limit(1), false);
        query.setUnique(true);
        return (RT) execute(query);
    }

    public void close() throws IOException {
        for (Query query : queries){
            query.closeAll();
        }        
    }
}

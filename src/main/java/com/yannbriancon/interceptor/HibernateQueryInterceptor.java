package com.yannbriancon.interceptor;

import com.yannbriancon.exception.NPlusOneQueryException;
import org.hibernate.EmptyInterceptor;
import org.hibernate.Transaction;
import org.hibernate.proxy.HibernateProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

@Component
@EnableConfigurationProperties(HibernateQueryInterceptorProperties.class)
public class HibernateQueryInterceptor extends EmptyInterceptor {

    private transient ThreadLocal<Long> threadQueryCount = new ThreadLocal<>();

    private transient ThreadLocal<Set<String>> threadPreviouslyLoadedEntities =
            ThreadLocal.withInitial(new EmptySetSupplier());

    private static final Logger LOGGER = LoggerFactory.getLogger(HibernateQueryInterceptor.class);

    private final HibernateQueryInterceptorProperties hibernateQueryInterceptorProperties;

    public HibernateQueryInterceptor(HibernateQueryInterceptorProperties hibernateQueryInterceptorProperties) {
        this.hibernateQueryInterceptorProperties = hibernateQueryInterceptorProperties;
    }

    /**
     * Start or reset the query count to 0 for the considered thread
     */
    public void startQueryCount() {
        threadQueryCount.set(0L);
    }

    /**
     * Get the query count for the considered thread
     */
    public Long getQueryCount() {
        return threadQueryCount.get();
    }

    /**
     * Increment the query count for the considered thread for each new statement if the count has been initialized
     *
     * @param sql Query to be executed
     * @return Query to be executed
     */
    @Override
    public String onPrepareStatement(String sql) {
        Long count = threadQueryCount.get();
        if (count != null) {
            threadQueryCount.set(count + 1);
        }
        return super.onPrepareStatement(sql);
    }

    /**
     * Reset previously loaded entities after the end of a transaction to avoid triggering
     * N+1 query exceptions because of loading same instance in two different transactions
     *
     * @param tx Transaction having been completed
     */
    @Override
    public void afterTransactionCompletion(Transaction tx) {
        threadPreviouslyLoadedEntities.set(new HashSet<>());
    }

    /**
     * Detect the N+1 queries by checking if two calls were made to getEntity for the same instance
     * <p>
     * The first call is made with the instance filled with a {@link HibernateProxy}
     * and the second is made after a query was executed to fetch the data in the Entity
     *
     * @param entityName Name of the entity to get
     * @param id         Id of the entity to get
     */
    @Override
    public Object getEntity(String entityName, Serializable id) {
        Set<String> previouslyLoadedEntities = threadPreviouslyLoadedEntities.get();

        if (previouslyLoadedEntities.contains(entityName + id)) {
            previouslyLoadedEntities.remove(entityName + id);
            threadPreviouslyLoadedEntities.set(previouslyLoadedEntities);
            logDetectedNPlusOneQuery(entityName);
        }

        previouslyLoadedEntities.add(entityName + id);
        threadPreviouslyLoadedEntities.set(previouslyLoadedEntities);

        return null;
    }

    /**
     * Log the detected N+1 query or throw an exception depending on the configured error level
     *
     * @param entityName Name of the entity on which the N+1 query has been detected
     */
    private void logDetectedNPlusOneQuery(String entityName) {
        String errorMessage = "N+1 query detected for entity: " + entityName;
        switch (hibernateQueryInterceptorProperties.getErrorLevel()) {
            case INFO:
                LOGGER.info(errorMessage);
                break;
            case WARN:
                LOGGER.warn(errorMessage);
                break;
            case ERROR:
                LOGGER.error(errorMessage);
                break;
            default:
                throw new NPlusOneQueryException(errorMessage);
        }
    }
}

class EmptySetSupplier implements Supplier<Set<String>> {
    public Set<String> get(){
        return new HashSet<>();
    }
}

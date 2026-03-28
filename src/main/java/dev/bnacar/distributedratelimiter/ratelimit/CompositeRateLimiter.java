package dev.bnacar.distributedratelimiter.ratelimit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Composite rate limiter that combines multiple rate limiting algorithms
 * with configurable combination logic.
 */
public class CompositeRateLimiter implements RateLimiter {
    
    private final List<LimitComponent> components;
    private final CombinationLogic combinationLogic;
    private final Map<String, Double> componentWeights;
    
    public CompositeRateLimiter(List<LimitComponent> components, CombinationLogic combinationLogic) {
        if (components == null || components.isEmpty()) {
            throw new IllegalArgumentException("Composite rate limiter must have at least one component");
        }
        
        this.components = new ArrayList<>(components);
        this.combinationLogic = combinationLogic;
        this.componentWeights = components.stream()
                .collect(Collectors.toMap(LimitComponent::getName, LimitComponent::getWeight));
        
        // Sort by priority for priority-based and hierarchical evaluation
        if (combinationLogic == CombinationLogic.PRIORITY_BASED || 
            combinationLogic == CombinationLogic.HIERARCHICAL_AND) {
            this.components.sort(Comparator.comparingInt(LimitComponent::getPriority).reversed());
        }
    }
    
    @Override
    public boolean tryConsume(int tokens) {
        return tryConsumeWithResult(tokens).allowed;
    }

    @Override
    public ConsumptionResult tryConsumeWithResult(int tokens) {
        boolean allowed;
        switch (combinationLogic) {
            case ALL_MUST_PASS:
                allowed = tryConsumeAllMustPass(tokens);
                break;
            case ANY_CAN_PASS:
                allowed = tryConsumeAnyCanPass(tokens);
                break;
            case WEIGHTED_AVERAGE:
                allowed = tryConsumeWeightedAverage(tokens);
                break;
            case HIERARCHICAL_AND:
                allowed = tryConsumeHierarchical(tokens);
                break;
            case PRIORITY_BASED:
                allowed = tryConsumePriorityBased(tokens);
                break;
            default:
                allowed = false;
        }
        return new ConsumptionResult(allowed, getCurrentTokens(), getCapacity());
    }
    
    @Override
    public void setCurrentTokens(int tokens) {
        for (LimitComponent component : components) {
            component.getRateLimiter().setCurrentTokens(tokens);
        }
    }
    
    /**
     * ALL_MUST_PASS: All components must allow the request.
     */
    private boolean tryConsumeAllMustPass(int tokens) {
        for (LimitComponent component : components) {
            if (!wouldAllow(component, tokens)) return false;
        }
        boolean allConsumed = true;
        for (LimitComponent component : components) {
            if (!component.tryConsume(tokens)) {
                allConsumed = false;
                break;
            }
        }
        return allConsumed;
    }
    
    /**
     * ANY_CAN_PASS: At least one component must allow the request.
     */
    private boolean tryConsumeAnyCanPass(int tokens) {
        for (LimitComponent component : components) {
            if (component.tryConsume(tokens)) return true;
        }
        return false;
    }
    
    /**
     * WEIGHTED_AVERAGE: Calculate weighted score and allow if above threshold.
     */
    private boolean tryConsumeWeightedAverage(int tokens) {
        double totalWeight = 0.0;
        double weightedScore = 0.0;
        for (LimitComponent component : components) {
            double weight = component.getWeight();
            totalWeight += weight;
            if (wouldAllow(component, tokens)) weightedScore += weight;
        }
        boolean allowed = (weightedScore / totalWeight) >= 0.5;
        if (allowed) {
            for (LimitComponent component : components) {
                if (wouldAllow(component, tokens)) component.tryConsume(tokens);
            }
        }
        return allowed;
    }
    
    /**
     * HIERARCHICAL_AND: Check components in hierarchical order (by scope).
     */
    private boolean tryConsumeHierarchical(int tokens) {
        Map<String, List<LimitComponent>> scopeGroups = components.stream()
                .collect(Collectors.groupingBy(c -> c.getScope() != null ? c.getScope() : "GLOBAL"));
        String[] scopeOrder = {"USER", "TENANT", "GLOBAL"};
        for (String scope : scopeOrder) {
            List<LimitComponent> scopeComponents = scopeGroups.get(scope);
            if (scopeComponents != null) {
                for (LimitComponent component : scopeComponents) {
                    if (!component.tryConsume(tokens)) return false;
                }
            }
        }
        for (Map.Entry<String, List<LimitComponent>> entry : scopeGroups.entrySet()) {
            if (!Arrays.asList(scopeOrder).contains(entry.getKey())) {
                for (LimitComponent component : entry.getValue()) {
                    if (!component.tryConsume(tokens)) return false;
                }
            }
        }
        return true;
    }
    
    /**
     * PRIORITY_BASED: Check highest priority components first, fail fast.
     */
    private boolean tryConsumePriorityBased(int tokens) {
        for (LimitComponent component : components) {
            if (!component.tryConsume(tokens)) return false;
        }
        return true;
    }
    
    private boolean wouldAllow(LimitComponent component, int tokens) {
        return component.getCurrentTokens() >= tokens;
    }
    
    @Override
    public int getCurrentTokens() {
        return components.stream().mapToInt(LimitComponent::getCurrentTokens).min().orElse(0);
    }
    
    @Override
    public int getCapacity() {
        return components.stream().mapToInt(component -> component.getRateLimiter().getCapacity()).sum();
    }
    
    @Override
    public int getRefillRate() {
        return (int) components.stream().mapToInt(component -> component.getRateLimiter().getRefillRate()).average().orElse(0);
    }
    
    @Override
    public long getLastRefillTime() {
        return components.stream().mapToLong(component -> component.getRateLimiter().getLastRefillTime()).max().orElse(System.currentTimeMillis());
    }
    
    public List<LimitComponent> getComponents() {
        return Collections.unmodifiableList(components);
    }
    
    public CombinationLogic getCombinationLogic() {
        return combinationLogic;
    }
    
    public Map<String, Double> getComponentWeights() {
        return Collections.unmodifiableMap(componentWeights);
    }
}

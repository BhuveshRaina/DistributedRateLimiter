    @Scheduled(fixedRateString = "${ratelimiter.adaptive.evaluation-interval-ms:30000}")
    public void evaluateAdaptations() {
        if (!enabled || redisTemplate == null) return;
        syncWithConfiguration();
        
        // ONLY evaluate keys that are explicitly marked as Adaptive Targets
        Map<Object, Object> targets = redisTemplate.opsForHash().entries(ADAPTIVE_TARGETS_KEY);
        if (targets != null) {
            for (Object targetObj : targets.keySet()) {
                evaluateKey(targetObj.toString());
            }
        }
    }

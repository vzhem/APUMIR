//! Fallback Chain
//! Каскадная стратегия установления соединения.
//! Пробует все возможные пути прежде чем отправить в Message Queue.

use super::nat_types::NatType;

/// Шаг каскадного поиска
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FallbackStep {
    DirectQuic,
    Ice,
    RelayTier1,
    RelayTier2,
    DhtLookup,
    SeedNode,
    StoreAndForward,
}

/// Результат попытки шага
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StepResult {
    Success,
    Failed,
    RetryLater,
}

/// Контекст соединения
#[derive(Debug, Clone)]
pub struct FallbackContext {
    pub local_nat: NatType,
    pub remote_nat: NatType,
    pub tier1_available: bool,
    pub tier2_available: bool,
    pub dht_available: bool,
    pub seed_available: bool,
}

impl FallbackContext {
    pub fn new(local_nat: NatType, remote_nat: NatType) -> Self {
        Self {
            local_nat,
            remote_nat,
            tier1_available: true,
            tier2_available: true,
            dht_available: true,
            seed_available: true,
        }
    }
}

/// Каскадная логика
pub struct FallbackChain {
    pub steps: Vec<FallbackStep>,
    current_index: usize,
}

impl FallbackChain {
    pub fn new(context: &FallbackContext) -> Self {
        let mut steps = Vec::new();

        // 1. Direct QUIC если оба NAT позволяют
        if context.local_nat.supports_direct_p2p() && context.remote_nat.supports_direct_p2p() {
            steps.push(FallbackStep::DirectQuic);
        }

        // 2. ICE если стратегия допускает
        let strategy = super::nat_types::NatDetector::recommend_strategy(
            &context.local_nat,
            &context.remote_nat,
        );

        if strategy.needs_ice() {
            steps.push(FallbackStep::Ice);
        }

        // 3. Relay Tier 1
        if context.tier1_available {
            steps.push(FallbackStep::RelayTier1);
        }

        // 4. Relay Tier 2
        if context.tier2_available {
            steps.push(FallbackStep::RelayTier2);
        }

        // 5. DHT
        if context.dht_available {
            steps.push(FallbackStep::DhtLookup);
        }

        // 6. Seed
        if context.seed_available {
            steps.push(FallbackStep::SeedNode);
        }

        // 7. Store-and-Forward (финальный fallback)
        steps.push(FallbackStep::StoreAndForward);

        Self {
            steps,
            current_index: 0,
        }
    }

    pub fn current_step(&self) -> Option<&FallbackStep> {
        self.steps.get(self.current_index)
    }

    pub fn advance(&mut self) {
        if self.current_index < self.steps.len() {
            self.current_index += 1;
        }
    }

    pub fn is_finished(&self) -> bool {
        self.current_index >= self.steps.len()
    }

    pub fn reset(&mut self) {
        self.current_index = 0;
    }

    pub fn total_steps(&self) -> usize {
        self.steps.len()
    }
}

//
// ========================= TESTS =========================
//

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::nat_types::NatType;

    #[test]
    fn test_full_cone_direct_first() {
        let ctx = FallbackContext::new(NatType::FullCone, NatType::FullCone);
        let chain = FallbackChain::new(&ctx);

        assert_eq!(chain.current_step(), Some(&FallbackStep::DirectQuic));
    }

    #[test]
    fn test_symmetric_skips_direct() {
        let ctx = FallbackContext::new(NatType::Symmetric, NatType::FullCone);
        let chain = FallbackChain::new(&ctx);

        assert_ne!(chain.current_step(), Some(&FallbackStep::DirectQuic));
    }

    #[test]
    fn test_store_and_forward_always_last() {
        let ctx = FallbackContext::new(NatType::Symmetric, NatType::Symmetric);
        let chain = FallbackChain::new(&ctx);

        assert_eq!(chain.steps.last(), Some(&FallbackStep::StoreAndForward));
    }

    #[test]
    fn test_advance_through_all_steps() {
        let ctx = FallbackContext::new(NatType::FullCone, NatType::FullCone);
        let mut chain = FallbackChain::new(&ctx);

        let total = chain.total_steps();

        for _ in 0..total {
            assert!(!chain.is_finished());
            chain.advance();
        }

        assert!(chain.is_finished());
    }

    #[test]
    fn test_reset_chain() {
        let ctx = FallbackContext::new(NatType::FullCone, NatType::FullCone);
        let mut chain = FallbackChain::new(&ctx);

        chain.advance();
        chain.advance();
        chain.reset();

        assert_eq!(chain.current_index, 0);
    }

    #[test]
    fn test_disable_tier1() {
        let mut ctx = FallbackContext::new(NatType::Symmetric, NatType::Symmetric);
        ctx.tier1_available = false;

        let chain = FallbackChain::new(&ctx);

        assert!(!chain.steps.contains(&FallbackStep::RelayTier1));
    }

    #[test]
    fn test_disable_dht() {
        let mut ctx = FallbackContext::new(NatType::Symmetric, NatType::Symmetric);
        ctx.dht_available = false;

        let chain = FallbackChain::new(&ctx);

        assert!(!chain.steps.contains(&FallbackStep::DhtLookup));
    }

    #[test]
    fn test_disable_seed() {
        let mut ctx = FallbackContext::new(NatType::Symmetric, NatType::Symmetric);
        ctx.seed_available = false;

        let chain = FallbackChain::new(&ctx);

        assert!(!chain.steps.contains(&FallbackStep::SeedNode));
    }

    #[test]
    fn test_ice_included_when_needed() {
        let ctx = FallbackContext::new(NatType::PortRestricted, NatType::PortRestricted);
        let chain = FallbackChain::new(&ctx);

        assert!(chain.steps.contains(&FallbackStep::Ice));
    }

    #[test]
    fn test_chain_has_at_least_store_and_forward() {
        let mut ctx = FallbackContext::new(NatType::Symmetric, NatType::Symmetric);
        ctx.tier1_available = false;
        ctx.tier2_available = false;
        ctx.dht_available = false;
        ctx.seed_available = false;

        let chain = FallbackChain::new(&ctx);

        assert_eq!(chain.steps, vec![FallbackStep::StoreAndForward]);
    }
}

package app.aaps.plugins.aps.openAPSAIMI.di

import app.aaps.plugins.aps.openAPSAIMI.steps.*
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * 🔧 AIMI Steps Provider Module - MTR Dagger Integration
 * 
 * Provides dependency injection configuration for the steps provider chain.
 * 
 * Providers instantiated:
 * - AIMIHealthConnectStepsProviderMTR (Health Connect integration)
 * - AIMIDatabaseStepsProviderMTR (Database fallback)
 * - AIMIPhoneStepsProviderMTR (Phone sensor)
 * - AIMICompositeStepsProviderMTR (Chain orchestrator)
 * 
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 */
@Module
class AIMIStepsProviderModuleMTR {
    
    /**
     * All providers are @Singleton so they're created once and reused.  
     * They're automatically injected via constructor @Inject.
     * 
     * The Composite provider is the main entry point and should be  
     * injected wherever steps data is needed.
     */
    
    // No explicit @Provides needed - Dagger handles @Inject constructors automatically
    // This module just ensures the dependencies are scanned
}

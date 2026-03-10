package app.aaps.plugins.aps.di

import dagger.Module

/**
 * 🔌 AIMI Physiological Module - MTR Dagger Configuration
 * 
 * SIMPLIFIED: All physiological components use @Inject constructor
 * and @Singleton annotation directly, so no explicit @Provides needed.
 * 
 * This module exists only for inclusion in ApsModule.
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Module
class AIMIPhysioModuleMTR {
    // Empty module - all components use @Inject constructor + @Singleton
    // Dagger handles dependency injection automatically
}

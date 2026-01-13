package app.aaps.plugins.aps.di

import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMILLMPhysioAnalyzerMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioBaselineModelMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioContextEngineMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioContextStoreMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioDataRepositoryMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioFeatureExtractorMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioManagerMTR
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * 🔌 AIMI Physiological Module - MTR Dagger Configuration
 * 
 * Dependency injection configuration for Physiological Assistant components.
 * 
 * All components are @Singleton to ensure:
 * - Single instance across app lifecycle
 * - Thread-safe shared state
 * - Consistent baseline/context
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Module
class AIMIPhysioModuleMTR {
    
    @Provides
    @Singleton
    fun providePhysioDataRepository(
        impl: AIMIPhysioDataRepositoryMTR
    ): AIMIPhysioDataRepositoryMTR = impl
    
    @Provides
    @Singleton
    fun providePhysioFeatureExtractor(
        impl: AIMIPhysioFeatureExtractorMTR
    ): AIMIPhysioFeatureExtractorMTR = impl
    
    @Provides
    @Singleton
    fun providePhysioBaselineModel(
        impl: AIMIPhysioBaselineModelMTR
    ): AIMIPhysioBaselineModelMTR = impl
    
    @Provides
    @Singleton
    fun providePhysioContextEngine(
        impl: AIMIPhysioContextEngineMTR
    ): AIMIPhysioContextEngineMTR = impl
    
    @Provides
    @Singleton
    fun providePhysioContextStore(
        impl: AIMIPhysioContextStoreMTR
    ): AIMIPhysioContextStoreMTR = impl
    
    @Provides
    @Singleton
    fun provideInsulinDecisionAdapter(
        impl: AIMIInsulinDecisionAdapterMTR
    ): AIMIInsulinDecisionAdapterMTR = impl
    
    @Provides
    @Singleton
    fun provideLLMPhysioAnalyzer(
        impl: AIMILLMPhysioAnalyzerMTR
    ): AIMILLMPhysioAnalyzerMTR = impl
    
    @Provides
    @Singleton
    fun providePhysioManager(
        impl: AIMIPhysioManagerMTR
    ): AIMIPhysioManagerMTR = impl
}

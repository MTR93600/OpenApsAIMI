        }
        
        // ═══════════════════════════════════════════════════════════════════
        // 🌀 PHASE-SPACE TRAJECTORY ANALYSIS (Feature Flag Protected)
        // ═══════════════════════════════════════════════════════════════════
        // Analyzes insulin-glucose dynamics as geometric trajectories rather
        // than purely temporal sequences. Enables harmonious control through
        // soft modulation of SMB/basal decisions.
        // See: docs/research/PKPD_TRAJECTORY_CONTROLLER.md
        // ═══════════════════════════════════════════════════════════════════
        
        if (preferences.get(BooleanKey.OApsAIMITrajectoryGuardEnabled)) {
            try {
                // 1. Build phase-space history (90 min default)
                val trajectoryHistory = trajectoryHistoryProvider.buildHistory(
                    nowMillis = currentTime,
                    historyMinutes = 90,
                    currentBg = bg,
                    currentDelta = delta.toDouble(),
                    currentAccel = bgacc,
                    insulinActivityNow = iobActivityNow,
                    iobNow = iob.toDouble(),
                    pkpdStage = insulinActionState.stage,
                    timeSinceLastBolus = if (lastBolusAgeMinutes.isFinite()) lastBolusAgeMinutes.toInt() else 120,
                    cobNow = cob.toDouble()
                )
                
                // 2. Define stable orbit from profile
                val stableOrbit = StableOrbit.fromProfile(
                    targetBg = targetBg.toDouble(),
                    basalRate = profile.current_basal
                )
                
                // 3. Analyze trajectory
                val trajectoryAnalysis = trajectoryGuard.analyzeTrajectory(
                    history = trajectoryHistory,
                    stableOrbit = stableOrbit
                )
                
                if (trajectoryAnalysis != null) {
                    
                    // 4. Log trajectory analysis to console (visible in rT)
                    consoleLog.add("═══════════════════════════════════════════════════════")
                    trajectoryAnalysis.toConsoleLog().forEach { line ->
                        consoleLog.add(sanitizeForJson(line))
                    }
                    consoleLog.add("═══════════════════════════════════════════════════════")
                    
                    // 5. Apply trajectory modulation (SOFT adjustments, non-blocking)
                    val modulation = trajectoryAnalysis.modulation
                    
                    if (modulation.isSignificant()) {
                        consoleLog.add("🌀 TRAJECTORY MODULATION APPLIED:")
                        
                        // Store original values for logging
                        val originalMaxSMB = maxSMB
                        val originalMaxIob = maxIob
                        val originalInterval = intervalsmb
                        
                        // Apply SMB damping
                        if (abs(modulation.smbDamping - 1.0) > 0.05) {
                            maxSMB *= modulation.smbDamping
                            maxSMBHB *= modulation.smbDamping
                            consoleLog.add("  SMB limits: %.2fU → %.2fU (%.2fx damping)".format(
                                Locale.US, originalMaxSMB, maxSMB, modulation.smbDamping
                            ))
                        }
                        
                        // Apply interval stretch
                        if (abs(modulation.intervalStretch - 1.0) > 0.05) {
                            intervalsmb = (intervalsmb * modulation.intervalStretch).toInt().coerceIn(1, 20)
                            consoleLog.add("  Interval: %dmin → %dmin".format(
                                originalInterval, intervalsmb
                            ))
                        }
                        
                        // Apply safety margin expansion
                        if (abs(modulation.safetyMarginExpand - 1.0) > 0.05) {
                            maxIob *= modulation.safetyMarginExpand
                            consoleLog.add("  MaxIOB: %.2fU → %.2fU".format(
                                Locale.US, originalMaxIob, maxIob
                            ))
                        }
                        
                        // Flag basal preference (for later decision logic)
                        if (modulation.basalPreference > 0.7) {
                            consoleLog.add("  ⚠️ Trajectory suggests TEMP BASAL over SMB")
                            consoleLog.add("     (basalPref: %.0f%%)".format(
                                Locale.US, modulation.basalPreference * 100
                            ))
                            // Note: Actual basal preference implementation would go in basal decision section
                        }
                        
                        consoleLog.add("  Reason: ${modulation.reason}")
                    }
                    
                    // 6. Handle critical warnings (notifications for HIGH/CRITICAL)
                    val criticalWarnings = trajectoryAnalysis.warnings.filter { 
                        it.severity >= WarningSeverity.HIGH 
                    }
                    
                    if (criticalWarnings.isNotEmpty()) {
                        consoleLog.add("🚨 TRAJECTORY WARNINGS:")
                        criticalWarnings.forEach { warning ->
                            consoleLog.add("  ${warning.severity.emoji()} [${warning.type}]")
                            consoleLog.add("     ${warning.message}")
                            consoleLog.add("     → ${warning.suggestedAction}")
                            
                            // Send UI notification for CRITICAL severity
                            if (warning.severity == WarningSeverity.CRITICAL) {
                                try {
                                    uiInteraction.addNotification(
                                        id = "trajectory_critical_${warning.type}",
                                        text = warning.message,
                                        level = 2 // HIGH priority
                                    )
                                } catch (e: Exception) {
                                    aapsLogger.error(LTag.APS, "Failed to send trajectory notification: ${e.message}")
                                }
                            }
                        }
                    }
                    
                    // 7. Log convergence prediction if available
                    trajectoryAnalysis.predictedConvergenceTime?.let { minutes ->
                        consoleLog.add("📊 Predicted convergence to stable orbit: ${minutes}min")
                    }
                }
                
            } catch (e: Exception) {
                consoleLog.add("⚠️ Trajectory Guard error: ${e.message}")
                aapsLogger.error(LTag.APS, "Trajectory Guard analysis failed", e)
                // Non-fatal: Continue with normal loop execution
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // Continue with standard AIMI decision logic
        // ═══════════════════════════════════════════════════════════════════
        
        // End FCL 11.0 Hoist. Next block uses the results.

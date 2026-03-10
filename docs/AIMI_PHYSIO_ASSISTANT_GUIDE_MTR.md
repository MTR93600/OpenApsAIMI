# 🏥 AIMI Physiological Assistant - User Guide

This guide explains how to configure and use the AIMI Physiological Assistant within the OpenAPS AIMI System.

## Overview

The Physiological Assistant is a module designed to integrate physiological data (Sleep, Activity, Heart Rate Variability) into the automated insulin delivery decision process. It aims to improve glycemic control by adapting the system's aggressiveness based on the user's physiological state (e.g., rest, stress, fatigue).

## Configuration

To access the settings, navigate to:
**Preferences -> OpenAPS AIMI -> 🏥 Physiological Assistant**

### 1. Enable Assistant
*   **Enable Assistant**: This is the master switch.
    *   **OFF**: The assistant is disabled; no physiological data influences insulin delivery.
    *   **ON**: The assistant runs in the background, collecting data and applying safety multipliers if conditions are met.

### 2. Data Sources
The assistant relies on external data sources, primarily synchronized via Google Health Connect.

*   **Sync Sleep Data**: Enables fetching sleep stages and sleep efficiency data.
    *   *Requirement*: A compatible sleep tracker (e.g., Oura, Whoop, Garmin) syncing to Health Connect.
    *   *Usage*: Used to detect deep sleep (restorative) versus fragmented sleep (stressful).
*   **Sync HRV & RHR**: Enables fetching Heart Rate Variability (HRV) and Resting Heart Rate (RHR).
    *   *Requirement*: A heart rate monitor syncing HRV/RHR to Health Connect.
    *   *Usage*: HRV is a key indicator of autonomic nervous system balance (stress vs. recovery). Low HRV may trigger safer insulin limits.

### 3. Analysis & Logs
*   **LLM Narrative Analysis**: (Optional) Sends physiological data summaries to the configured LLM (e.g., OpenAI, Gemini) to generate a daily health narrative.
    *   *Note*: Requires an active active internet connection and valid API key in AI Assistant settings.
*   **Debug Logging**: Enables detailed logging of physiological states and decision logic to the console/logcat. Useful for troubleshooting.

## How it Works

When enabled, the assistant calculates **Safety Multipliers** that modulate:
1.  **Max SMB**: Limits the size of Super Micro Boluses.
2.  **Basal Rate**: Adjusts the maximum allowed basal rate.
3.  **ISF (Insulin Sensitivity Factor)**: Slightly modifies sensitivity in extreme states (e.g., high stress).

### Key Physiological States
*   **Rest/Recovery**: High Sleep Score + High HRV relative to baseline. -> *Standard or slightly optimized profile.*
*   **Stress/Fatigue**: Poor Sleep + Low HRV + High RHR. -> *More conservative profile (lower Max SMB, higher safety guards).*
*   **Activity**: High Step Count (detected via Phone or Watch). -> *Sensitivity adjustments (handled by Steps Manager).*

## Troubleshooting

*   **No Data**: Ensure you have granted "Health Connect" permissions to OpenAPS AIMI and that your wearable app is syncing to Health Connect.
*   **"Physio Multipliers applied" in logs**: This confirms the assistant is active and modifying settings.

---
*MTR - OpenAPS AIMI Project*

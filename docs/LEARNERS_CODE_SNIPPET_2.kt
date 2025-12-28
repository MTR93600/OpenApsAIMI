// 📊 ================================================================
// LEARNERS INFO: Populate finalResult for RT visibility
// ================================================================
if (learnersSummary.isNotEmpty()) {
    // 1. Set dedicated field
    finalResult.learnersInfo = learnersSummary
    
    // 2. Append to reason (visible in RT's main "reason" field)
    finalResult.reason.append("; [").append(learnersSummary).append("]")
    
    // 3. Log for debugging
    consoleLog.add("📊 Learners applied to finalResult.reason: [" + learnersSummary + "]")
}

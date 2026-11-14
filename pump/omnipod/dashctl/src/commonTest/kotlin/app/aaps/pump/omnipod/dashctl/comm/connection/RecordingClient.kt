package app.aaps.pump.omnipod.dashctl.comm.connection

/** Records the calls of [DashPodConnection]; the session is a flag, nothing is sent. */
internal class RecordingClient : DashLinkClient {

    var paired = false
    var failSession = false

    /** The link lock; a test sets it to play a command (or a session set up) that runs now. */
    var locked = false
    val calls = mutableListOf<String>()
    var attached: DashPodLink? = null
    private var sessionOn: DashPodLink? = null

    override fun isPaired(): Boolean = paired

    override fun hasSession(): Boolean {
        val link = attached ?: return false
        return sessionOn === link && link.isUsable
    }

    override fun tryLockLink(): Boolean {
        if (locked) return false
        locked = true
        return true
    }

    override fun unlockLink() {
        check(locked) { "unlock without lock" }
        locked = false
    }

    override fun attachLink(link: DashPodLink) {
        check(locked) { "attachLink must be called with the link lock held" }
        calls += "attach ${link.number}"
        attached = link
        sessionOn = null
    }

    override fun startSession() {
        check(locked) { "startSession must be called with the link lock held" }
        calls += "session ${attached?.number}"
        if (failSession) throw IllegalStateException("the pod sent a wrong RES")
        sessionOn = attached
    }
}

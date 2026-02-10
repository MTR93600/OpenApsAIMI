package app.aaps.plugins.sync.nsShared

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.databinding.DialogRemoteAccessLoginBinding
import dagger.android.support.DaggerDialogFragment
import javax.inject.Inject

/**
 * Login dialog for Remote Control access.
 */
class RemoteAccessLoginDialog : DaggerDialogFragment() {
    
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var authManager: RemoteAccessAuthManager
    
    private var _binding: DialogRemoteAccessLoginBinding? = null
    private val binding get() = _binding!!
    
    var onUnlocked: (() -> Unit)? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogRemoteAccessLoginBinding.inflate(inflater, container, false)
        isCancelable = false
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnUnlock.setOnClickListener {
            val password = binding.passwordInput.text.toString()
            
            if (password.isEmpty()) {
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.text = rh.gs(R.string.password_required)
                return@setOnClickListener
            }
            
            if (authManager.verifyPassword(password)) {
                // Success
                aapsLogger.info(LTag.NSCLIENT, "[RemoteAccess] Access granted")
                dismiss()
                onUnlocked?.invoke()
            } else {
                // Failed
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.text = rh.gs(R.string.remote_access_invalid_password)
                binding.passwordInput.text?.clear()
            }
        }
        
        binding.btnCancel.setOnClickListener {
            aapsLogger.info(LTag.NSCLIENT, "[RemoteAccess] Access cancelled")
            dismiss()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

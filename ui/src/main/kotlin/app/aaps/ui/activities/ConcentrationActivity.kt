package app.aaps.ui.activities

import android.os.Bundle
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.ui.dialogs.ConcentrationDialog

class ConcentrationActivity : TranslatedDaggerAppCompatActivity(){

    @Override
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val concentrationDialog = ConcentrationDialog()
        concentrationDialog.helperActivity = this
        concentrationDialog.show(supportFragmentManager, "Insulin Confirmation")
    }
}
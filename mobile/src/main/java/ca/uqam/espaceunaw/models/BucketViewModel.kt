package ca.uqam.espaceunaw.models

import androidx.lifecycle.ViewModel;
import org.json.JSONObject

class BucketViewModel(val json: JSONObject) : ViewModel() {
    // TODO: Implement the ViewModel
    init {
        val id = json.getString("id")
        //val eventCount = json.getInt("")
    }
}

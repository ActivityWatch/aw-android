package net.activitywatch.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import net.activitywatch.android.watcher.UsageStatsWatcher

// enum for the onboarding pages
enum class OnboardingPage {
    WELCOME,
    //FEATURES,
    ACCESSIBILITY_PERMISSION
}

class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val numPages = OnboardingPage.values().size

        viewPager.adapter = OnboardingAdapter(this)
        TabLayoutMediator(tabLayout, viewPager) { _, _ -> }.attach()

        val nextButton = findViewById<Button>(R.id.nextButton)
        val backButton = findViewById<Button>(R.id.backButton)

        // helper function to update texts/visibility of buttons on page change
        fun updateButtons() {
            nextButton.text = if (viewPager.currentItem == numPages - 1) "Finish" else "Continue"
            backButton.visibility = if (viewPager.currentItem > 0) View.VISIBLE else View.GONE
        }

        // If not users first time, skip to the last page
        val prefs = AWPreferences(this)
        if (!prefs.isFirstTime()) {
            viewPager.currentItem = OnboardingPage.ACCESSIBILITY_PERMISSION.ordinal
            updateButtons()
        }

        nextButton.setOnClickListener {
            val currentItem = viewPager.currentItem
            if (currentItem < numPages - 1) {
                viewPager.currentItem = currentItem + 1
            } else {
                // Handle finish button click (e.g., navigate to the main activity)
                // First, check if the user has granted the usage permission
                if(UsageStatsWatcher.isUsageAllowed(this)) {
                    AWPreferences(this).setFirstTimeRunFlag()
                    finish()
                } else {
                    // Show a snackbar and don't finish the activity
                    val snackbar = Snackbar.make(viewPager, "Please grant usage access permission, they are necessary for the core function of the app.", Snackbar.LENGTH_LONG)
                    snackbar.show()
                }
            }
            updateButtons()
        }

        backButton.setOnClickListener {
            val currentItem = viewPager.currentItem
            if (currentItem > 0) {
                viewPager.currentItem = currentItem - 1
            }
            updateButtons()
        }

        // Update the text of the next button based on the current page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtons()
            }
        })
    }

    override fun onBackPressed() {
        // If back button is pressed, exit the app,
        // since we don't want to allow the user to accidentally skip onboarding.
        // (Google Play policy, due to sensitive permissions)
        // https://developer.android.com/distribute/best-practices/develop/restrictions-non-sdk-interfaces#back-button
        finishAffinity()
    }
}

class OnboardingAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = OnboardingPage.values().size

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            OnboardingPage.WELCOME.ordinal -> WelcomeFragment()
            //OnboardingPage.FEATURES.ordinal -> FeaturesFragment()
            OnboardingPage.ACCESSIBILITY_PERMISSION.ordinal -> PermissionsFragment()
            else -> throw IllegalArgumentException("Invalid position")
        }
    }
}


class WelcomeFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_welcome, container, false)
    }
}

class FeaturesFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_features, container, false)
    }
}


class PermissionsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_permissions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Handle grant usage button click
        view.findViewById<Button>(R.id.btnGrantUsagePermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        // Handle grant accessibility permissions
        view.findViewById<Button>(R.id.btnGrantAccessibilityPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // When fragment is resumed, check if the permission has been granted
    override fun onResume() {
        super.onResume()

        // Get current permission status
        val usagePermissionGranted = UsageStatsWatcher.isUsageAllowed(requireContext())
        val accessibilityPermissionGranted = UsageStatsWatcher.isAccessibilityAllowed(requireContext())

        // Disable buttons if permissions granted
        view?.findViewById<Button>(R.id.btnGrantUsagePermission)?.isEnabled = !usagePermissionGranted
        view?.findViewById<Button>(R.id.btnGrantAccessibilityPermission)?.isEnabled = !accessibilityPermissionGranted

        // Set the checkbox/x mark based on the permission status
        view?.findViewById<ImageView>(R.id.checkmarkUsage)?.setImageResource(if(usagePermissionGranted) R.drawable.ic_checkmark else R.drawable.ic_x)
        view?.findViewById<ImageView>(R.id.checkmarkAccessibility)?.setImageResource(if(accessibilityPermissionGranted) R.drawable.ic_checkmark else R.drawable.ic_x)
    }
}
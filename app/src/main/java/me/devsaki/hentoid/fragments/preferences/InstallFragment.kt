package me.devsaki.hentoid.fragments.preferences

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import me.devsaki.hentoid.R
import me.devsaki.hentoid.activities.PrefsActivity
import me.devsaki.hentoid.databinding.FragmentInstallBinding
import me.devsaki.hentoid.databinding.FragmentLicensesBinding
import me.devsaki.hentoid.util.ApkInstall
import me.devsaki.hentoid.viewmodels.ViewModelFactory

class InstallFragment : Fragment(R.layout.fragment_install) {

    private var _binding: FragmentInstallBinding? = null
    private val binding get() = _binding!!

    private lateinit var installer: ApkInstall

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInstallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity: PrefsActivity = requireActivity() as PrefsActivity
        val vmFactory = ViewModelFactory(activity.application)
        installer = ViewModelProvider(this, vmFactory)[ApkInstall::class.java]
        installer.install(activity.getInstallApkUri())
    }
}
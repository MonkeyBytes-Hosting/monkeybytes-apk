package com.monkeybytes.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.monkeybytes.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnDashboard.setOnClickListener { openWeb("https://dash.monkey-network.xyz/") }
        binding.btnDiscord.setOnClickListener { openExternal("https://discord.gg/kQbasjfGaM") }
        binding.btnStatus.setOnClickListener { openWeb("https://status.mbint.dpdns.org/") }
    }

    private fun openWeb(url: String) {
        startActivity(Intent(this, WebViewActivity::class.java).putExtra("url", url))
    }

    private fun openExternal(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

package com.example.qasidatosong

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.qasidatosong.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastOutputFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.convertButton.setOnClickListener {
            val poemText = binding.poemEditText.text?.toString()?.trim().orEmpty()
            startConversion(poemText)
        }

        binding.shareButton.setOnClickListener {
            lastOutputFile?.let { shareFile(it) }
        }
    }

    private fun startConversion(poemText: String) {
        if (poemText.isBlank()) {
            Toast.makeText(this, "الرجاء كتابة نص القصيدة أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        setBusyState(true, "جارٍ التحضير...")

        val generator = SongGenerator(applicationContext)
        Thread {
            generator.generateSong(poemText, object : SongGenerator.Callback {
                override fun onProgress(message: String) {
                    runOnUiThread {
                        binding.statusText.text = message
                    }
                }

                override fun onSuccess(outputFile: File) {
                    runOnUiThread {
                        lastOutputFile = outputFile
                        setBusyState(false, "تم إنشاء الأغنية بنجاح 🎵")
                        binding.shareButton.visibility = android.view.View.VISIBLE
                        Toast.makeText(
                            this@MainActivity,
                            "تم الحفظ في: ${outputFile.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        setBusyState(false, "")
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            })
        }.start()
    }

    private fun setBusyState(busy: Boolean, statusMessage: String) {
        binding.convertButton.isEnabled = !busy
        binding.progressLayout.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.statusText.text = statusMessage
        if (!busy) {
            binding.progressLayout.visibility = android.view.View.GONE
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "مشاركة الأغنية"))
    }
}

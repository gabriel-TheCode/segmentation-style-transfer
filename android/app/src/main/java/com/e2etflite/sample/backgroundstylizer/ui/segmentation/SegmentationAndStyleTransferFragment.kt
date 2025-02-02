package com.e2etflite.sample.backgroundstylizer.ui.segmentation

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.e2etflite.sample.backgroundstylizer.MainActivity
import com.e2etflite.sample.backgroundstylizer.R
import com.e2etflite.sample.backgroundstylizer.databinding.FragmentSelfie2segmentationBinding
import com.e2etflite.sample.backgroundstylizer.ui.camera.CameraFragment
import com.e2etflite.sample.backgroundstylizer.ui.camera.CameraFragmentDirections
import com.e2etflite.sample.backgroundstylizer.ui.style.StyleFragment
import com.e2etflite.sample.backgroundstylizer.utils.ImageUtils
import kotlinx.android.synthetic.main.fragment_selfie2segmentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.getKoin
import org.koin.android.viewmodel.ext.android.viewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * A simple [Fragment] subclass.
 * Use the [SegmentationAndStyleTransferFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 * This is where we show both the captured input image and the output image
 */
class SegmentationAndStyleTransferFragment : Fragment(),
        SearchFragmentNavigationAdapter.SearchClickItemListener,
        StyleFragment.OnListFragmentInteractionListener {

    private val args: SegmentationAndStyleTransferFragmentArgs by navArgs()
    private lateinit var filePath: String
    private var finalBitmap: Bitmap? = null
    private var finalBitmapWithStyle: Bitmap? = null

    // Koin inject ViewModel
    private val viewModel: SegmentationAndStyleTransferViewModel by viewModel()

    // DataBinding
    private lateinit var binding: FragmentSelfie2segmentationBinding
    private lateinit var photoFile: File
    private lateinit var backgroundPhotoFile: File

    // RecyclerView
    private lateinit var mSearchFragmentNavigationAdapter: SearchFragmentNavigationAdapter

    //
    private lateinit var styleTransferModelExecutor: StyleTransferModelExecutor

    private lateinit var scaledBitmap: Bitmap
    private lateinit var selfieBitmap: Bitmap
    private lateinit var backgroundBitmap: Bitmap
    private var outputBitmapFinal: Bitmap? = null
    private var inferenceTime: Long = 0L
    private val stylesFragment: StyleFragment = StyleFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true) // enable toolbar

        retainInstance = true
        filePath = args.rootDir
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        binding = FragmentSelfie2segmentationBinding.inflate(inflater)
        binding.lifecycleOwner = this
        binding.viewModelXml = viewModel

        // RecyclerView setup
        mSearchFragmentNavigationAdapter =
                SearchFragmentNavigationAdapter(
                        requireActivity(),
                        viewModel.currentList,
                        this
                )
        binding.recyclerViewStyles.apply {
            setHasFixedSize(true)
            layoutManager =
                    LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            adapter = mSearchFragmentNavigationAdapter

        }

        // Initialize class with Koin
        styleTransferModelExecutor = get()

        getKoin().setProperty(getString(R.string.koinStyle), viewModel.stylename)

        observeViewModel()

        // Click on Style picker
        binding.chooseStyleTextView.setOnClickListener {
            stylesFragment.show(requireActivity().supportFragmentManager, "StylesFragment")
        }

        // Listeners for toggle buttons
        binding.imageToggleLeft.setOnClickListener {

            // Make input ImageView visible
            binding.imageviewInput.visibility = View.VISIBLE
            // Make output Image gone
            binding.imageviewOutput.visibility = View.GONE
        }
        binding.imageToggleRight.setOnClickListener {

            // Make input ImageView gone
            binding.imageviewInput.visibility = View.GONE
            // Make output Image visible
            binding.imageviewOutput.visibility = View.VISIBLE
        }

        binding.frameOutputBackground.setOnClickListener {
            val intent = Intent()
            intent.type = "image/*"
            intent.action = Intent.ACTION_GET_CONTENT
            startActivityForResult(
                Intent.createChooser(intent, "Select Picture"),
                PICK_BACKGROUND_IMAGE_REQUEST
            )
        }

        return binding.root
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_BACKGROUND_IMAGE_REQUEST && resultCode == Activity.RESULT_OK && data != null && data.data != null) {

            val filePath: Uri? = data.data
            if (filePath != null) {
                Toast.makeText(requireContext(), filePath.path, Toast.LENGTH_SHORT).show()
                backgroundBitmap =
                    BitmapFactory.decodeStream(
                        requireActivity().contentResolver.openInputStream(
                            filePath.toString().toUri()
                        )
                    )

                val styledBitmap = viewModel.cropBitmapWithMaskOver(backgroundBitmap, outputBitmapFinal)

                Glide.with(imageview_background.context)
                    .load(styledBitmap)
                    .fitCenter()
                    .into(imageview_background)
            }
        }
    }

    private fun observeViewModel() {

        viewModel.styledBitmap.observe(
                requireActivity()
        ) { resultImage ->
            if (resultImage != null) {
                /*Glide.with(activity!!)
                    .load(resultImage.styledImage)
                    .fitCenter()
                    .into(binding.imageViewStyled)*/

                // Set this to use with save function
                finalBitmapWithStyle = viewModel.cropBitmapWithMaskForStyle(
                    resultImage.styledImage,
                    outputBitmapFinal
                )

                binding.imageviewStyled.setImageBitmap(
                    viewModel.cropBitmapWithMaskForStyle(
                        resultImage.styledImage,
                        outputBitmapFinal
                    )
                )//selfieBitmap
            }
        }

        // Observe style transfer procedure
        viewModel.inferenceDone.observe(
                requireActivity()
        ) { loadingDone ->
            when (loadingDone) {

                true -> binding.progressbarStyle.visibility = View.GONE
                else -> binding.progressbarStyle.visibility = View.VISIBLE
            }
        }

        viewModel.totalTimeInference.observe(
                requireActivity()
        ) { time ->
            binding.inferenceInfoStyle.text = "Total process time: ${time}ms"
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (filePath.startsWith("/storage")) {
            photoFile = File(filePath)

            Glide.with(imageview_input.context)
                    .load(photoFile)
                    .fitCenter()
                    .into(imageview_input)

            // Make input ImageView visible
            binding.imageviewInput.visibility = View.VISIBLE

            selfieBitmap = BitmapFactory.decodeFile(filePath)
            imageview_input.setImageBitmap(selfieBitmap)

            lifecycleScope.launch(Dispatchers.Default) {
                val (outputBitmap, inferenceTime) = viewModel.cropPersonFromPhoto(selfieBitmap)
                outputBitmapFinal = outputBitmap
                withContext(Dispatchers.Main) {

                    // Make input ImageView gone
                    binding.imageviewInput.visibility = View.GONE

                    updateUI(outputBitmap, inferenceTime)
                    finalBitmap = outputBitmap

                    // Make output Image visible
                    binding.imageviewOutput.visibility = View.VISIBLE

                }
            }
        } else {
            selfieBitmap =
                    BitmapFactory.decodeStream(
                            requireActivity().contentResolver.openInputStream(
                                    filePath.toUri()
                            )
                    )

            Glide.with(imageview_input.context)
                    .load(selfieBitmap)
                    .fitCenter()
                    .into(imageview_input)

            // Make input ImageView visible
            binding.imageviewInput.visibility = View.VISIBLE

            lifecycleScope.launch(Dispatchers.Default) {
                val (outputBitmap, inferenceTime) = viewModel.cropPersonFromPhoto(selfieBitmap)
                outputBitmapFinal = outputBitmap
                withContext(Dispatchers.Main) {

                    // Make input ImageView gone
                    binding.imageviewInput.visibility = View.GONE

                    updateUI(outputBitmap, inferenceTime)
                    finalBitmap = outputBitmap

                    // Make output Image visible
                    binding.imageviewOutput.visibility = View.VISIBLE

                }
            }

        }

    }

    private fun updateUI(outputBitmap: Bitmap?, inferenceTime: Long) {
        progressbar.visibility = View.GONE
        imageview_input.visibility = View.INVISIBLE
        Glide.with(imageview_output.context)
                .load(outputBitmap)
                .fitCenter()
                .into(imageview_output)
        //imageview_output?.setImageBitmap(outputBitmap)
        inference_info.text = "Total process time: " + inferenceTime.toString() + "ms"

        //showStyledImage("mona.JPG")
    }


    private fun showStyledImage(style: String) {
        lifecycleScope.launch(Dispatchers.Default) {

            viewModel.onApplyStyle(
                    requireActivity(), scaledBitmap, style
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // clean up coroutine job
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> saveImageToSDCard(finalBitmapWithStyle)
        }
        return super.onOptionsItemSelected(item)
    }

    private fun saveImageToSDCard(bitmap: Bitmap?): String {

        val file = File(
                MainActivity.getOutputDirectory(requireContext()),
                SimpleDateFormat(
                        FILENAME_FORMAT, Locale.US
                ).format(System.currentTimeMillis()) + "_segmentation_and_style_transfer.jpg"
        )

        ImageUtils.saveBitmap(bitmap, file)
        Toast.makeText(context, "saved to " + file.absolutePath.toString(), Toast.LENGTH_SHORT)
                .show()

        return file.absolutePath

    }

    companion object {
        private const val TAG = "SegmentationAndStyleTransferFragment"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        const val MODEL_WIDTH = 256
        const val MODEL_HEIGHT = 256
        private const val PICK_BACKGROUND_IMAGE_REQUEST = 1025
    }

    override fun onListItemClick(itemIndex: Int, sharedImage: ImageView?, type: String) {

        // Upon click show rogress bar
        binding.progressbarStyle.visibility = View.VISIBLE
        // make placeholder gone
        imageview_placeholder.visibility = View.GONE

        // Created scaled version of bitmap for model input.
        scaledBitmap = Bitmap.createScaledBitmap(
                selfieBitmap,
                MODEL_WIDTH,
                MODEL_HEIGHT, true
        )

        showStyledImage(type)
        getKoin().setProperty(getString(R.string.koinStyle), type)
        viewModel.setStyleName(type)

    }

    override fun onListFragmentInteraction(item: String) {
    }

    fun methodToStartStyleTransfer(item: String) {

        stylesFragment.dismiss()

        scaledBitmap = Bitmap.createScaledBitmap(
                selfieBitmap,
                MODEL_WIDTH,
                MODEL_HEIGHT, true
        )

        showStyledImage(item)
        getKoin().setProperty(getString(R.string.koinStyle), item)
        viewModel.setStyleName(item)
    }

}
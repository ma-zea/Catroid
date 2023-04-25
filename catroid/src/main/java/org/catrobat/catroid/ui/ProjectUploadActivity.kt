/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2022 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.ui

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import com.google.android.material.textfield.TextInputLayout
import com.google.common.base.Charsets
import com.google.common.io.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.catrobat.catroid.ProjectManager
import org.catrobat.catroid.R
import org.catrobat.catroid.common.Constants
import org.catrobat.catroid.common.Constants.CATROBAT_EXTENSION
import org.catrobat.catroid.common.FlavoredConstants
import org.catrobat.catroid.content.Project
import org.catrobat.catroid.databinding.ActivityUploadBinding
import org.catrobat.catroid.databinding.DialogReplaceApiKeyBinding
import org.catrobat.catroid.databinding.DialogUploadUnchangedProjectBinding
import org.catrobat.catroid.exceptions.ProjectException
import org.catrobat.catroid.io.ProjectAndSceneScreenshotLoader
import org.catrobat.catroid.io.asynctask.ProjectLoadTask.ProjectLoadListener
import org.catrobat.catroid.retrofit.models.ProjectUploadResponseApi
import org.catrobat.catroid.transfers.ProjectUploadTask
import org.catrobat.catroid.io.asynctask.ProjectLoader.ProjectLoadListener
import org.catrobat.catroid.io.asynctask.loadProject
import org.catrobat.catroid.io.asynctask.renameProject
import org.catrobat.catroid.transfers.GetUserProjectsTask
import org.catrobat.catroid.transfers.TagsTask
import org.catrobat.catroid.transfers.TokenTask
import org.catrobat.catroid.transfers.project.ResultReceiverWrapper
import org.catrobat.catroid.transfers.project.ResultReceiverWrapperInterface
import org.catrobat.catroid.ui.controller.ProjectUploadController
import org.catrobat.catroid.ui.controller.ProjectUploadController.ProjectUploadInterface
import org.catrobat.catroid.ui.recyclerview.dialog.TextInputDialog
import org.catrobat.catroid.ui.recyclerview.dialog.textwatcher.InputWatcher
import org.catrobat.catroid.utils.FileMetaDataExtractor
import org.catrobat.catroid.utils.NetworkConnectionMonitor
import org.catrobat.catroid.utils.ProjectZipper
import org.catrobat.catroid.utils.ToastUtil
import org.catrobat.catroid.utils.Utils
import org.catrobat.catroid.web.ServerAuthenticationConstants.DEPRECATED_TOKEN_LENGTH
import org.koin.android.ext.android.inject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Objects
import java.util.regex.Matcher
import java.util.regex.Pattern

private const val WEB_REQUEST_BRICK = "WebRequestBrick"
private const val BACKGROUND_REQUEST_BRICK = "BackgroundRequestBrick"
private const val LOOK_REQUEST_BRICK = "LookRequestBrick"
private const val OPEN_URL_BRICK = "OpenUrlBrick"
private const val WIKI_URL =
    "<a href='https://catrob.at/webbricks'>" + "https://catrob.at/webbricks</a>"
private const val LICENSE_TO_PLAY_URL =
    "<a href='https://catrob.at/ltp'>" + "https://catrob.at/ltp</a>"
private const val PROGRAM_NAME_START_TAG = "<programName>"
private const val PROGRAM_NAME_END_TAG = "</programName>"
private const val THUMBNAIL_SIZE = 100
private val TAG = ProjectUploadActivity::class.java.simpleName

const val PROJECT_DIR = "projectDir"
const val SIGN_IN_CODE = 42
const val NUMBER_OF_UPLOADED_PROJECTS = "number_of_uploaded_projects"

open class ProjectUploadActivity : BaseActivity(),
    ProjectLoadListener {

    private lateinit var project: Project
    private lateinit var xmlFile: File
    private lateinit var xml: String
    private lateinit var originalProjectName: String
    private lateinit var backUpXml: String
    private lateinit var apiMatcher: Matcher

    private var uploadProgressDialog: AlertDialog? = null

    private val nameInputTextWatcher = NameInputTextWatcher()
    private var enableNextButton = true
    private var notesAndCreditsScreen = false

    private val projectManager: ProjectManager by inject()
    private val connectionMonitor: NetworkConnectionMonitor by inject()

    private lateinit var binding: ActivityUploadBinding
    private lateinit var dialogUploadUnchangedProjectBinding: DialogUploadUnchangedProjectBinding
    private lateinit var dialogReplaceApiKeyBinding: DialogReplaceApiKeyBinding
    private var tags: List<String> = ArrayList()

    private lateinit var sharedPreferences: SharedPreferences

    private val tokenTask: TokenTask by inject()
    private val tagsTask: TagsTask by inject()

    // Used for uploading callback to stay consistent with API calls
    private val projectUploadTask: ProjectUploadTask by inject()

    // Used for zipping and uploading in background
    private var projectUploadJob: Job? = null

    private val getUserProjectsTask: GetUserProjectsTask by inject()

    private var getUserProjectsJob: Job? = null

    private var projectNamesOfUser: MutableList<String> = mutableListOf()

    private var extractProjectNamesFromResponseJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUploadBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setTitle(R.string.upload_project_dialog_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        notesAndCreditsScreen = false
        setShowProgressBar(true)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        projectUploadTask.clear()
        projectUploadTask.getProjectUploadResponse()
            .observe(this) { projectUploadResponse ->
                projectUploadResponse?.let {
                    showSuccessDialog(projectUploadResponse)
                } ?: run {
                    showErrorDialog(projectUploadTask.getErrorMessage())
                }
            }

        getUserProjectsTask.clear()
        getUserProjectsTask.getUserProjectsResponse()
            .observe(this) { getUserProjectsResponse ->
                getUserProjectsResponse?.let {
                    Log.d(TAG, "We got a response!")
                    extractProjectNamesFromResponseJob = GlobalScope.launch(Dispatchers.Main) {
                        for(response in getUserProjectsResponse) {
                            projectNamesOfUser.add(response.name)
                        }
                    }
                } ?: run {
                    Log.d(TAG, "We got no response, something failed")
                }
            }

        loadProjectActivity()
    }

    override fun onLoadFinished(success: Boolean) {
        if (success) {
            loadProjectActivity()
        } else {
            ToastUtil.showError(this, R.string.error_load_project)
            setShowProgressBar(false)
            finish()
        }
    }

    private fun loadProjectActivity() {
        getTags()
        project = projectManager.currentProject
        verifyUserIdentity()
        getAllUserProjects()
    }

    protected fun onCreateView() {
        val thumbnailSize = THUMBNAIL_SIZE
        val screenshotLoader = ProjectAndSceneScreenshotLoader(
            thumbnailSize,
            thumbnailSize
        )

        screenshotLoader.loadAndShowScreenshot(
            project.directory.name,
            screenshotLoader.getScreenshotSceneName(project.directory),
            false,
            findViewById(R.id.project_image_view)
        )

        binding.projectSizeView.text =
            FileMetaDataExtractor.getSizeAsString(project.directory, this)

        if (!projectManager.isChangedProject(project)) {
            showUploadIsUnchangedDialog()
        }

        binding.inputProjectName.editText?.setText(project.name)
        binding.inputProjectDescription.editText?.setText(project.description)
        binding.inputProjectNotesAndCredits.editText?.setText(project.notesAndCredits)
        binding.inputProjectName.editText?.addTextChangedListener(nameInputTextWatcher)
        originalProjectName = project.name

        checkCodeForApiKey()
        setShowProgressBar(false)
        setNextButtonEnabled(true)
    }

    private fun showUploadIsUnchangedDialog() {
        dialogUploadUnchangedProjectBinding =
            DialogUploadUnchangedProjectBinding.inflate(layoutInflater)
        dialogUploadUnchangedProjectBinding.unchangedUploadUrl.movementMethod =
            LinkMovementMethod.getInstance()

        val warningURL = getString(
            R.string.unchanged_upload_url,
            LICENSE_TO_PLAY_URL

        )
        dialogUploadUnchangedProjectBinding.unchangedUploadUrl.text = Html.fromHtml(warningURL)

        val alertDialog = AlertDialog.Builder(this)
            .setTitle(R.string.warning)
            .setView(dialogUploadUnchangedProjectBinding.root)
            .setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int -> finish() }
            .setCancelable(false)
            .create()

        alertDialog.show()
    }

    override fun onDestroy() {
        if (uploadProgressDialog?.isShowing == true) {
            uploadProgressDialog?.dismiss()
        }
        projectUploadJob?.cancel()
        getUserProjectsJob?.cancel()
        extractProjectNamesFromResponseJob?.cancel()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_next, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.next).isEnabled = enableNextButton
        return true
    }

    override fun onBackPressed() {
        if (notesAndCreditsScreen) {
            setScreen(notesAndCreditsScreen)
            notesAndCreditsScreen = false
        } else {
            loadBackup()
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.next -> onNextButtonClick()
            android.R.id.home -> {
                if (notesAndCreditsScreen) {
                    setScreen(notesAndCreditsScreen)
                    notesAndCreditsScreen = false
                }
                return super.onOptionsItemSelected(item)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    fun setShowProgressBar(show: Boolean) {
        findViewById<View>(R.id.progress_bar).visibility = if (show) View.VISIBLE else View.GONE
        binding.uploadLayout.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun setNextButtonEnabled(enabled: Boolean) {
        enableNextButton = enabled
        invalidateOptionsMenu()
    }

    private fun onNextButtonClick() {
        Utils.hideStandardSystemKeyboard(this)

        if (!notesAndCreditsScreen) {
            val name = binding.inputProjectName.editText?.text.toString().trim()
            val error = nameInputTextWatcher.validateName(name)

            error?.let {
                binding.inputProjectName.error = it
                return
            }

            if (checkIfProjectNameAlreadyExists(binding.inputProjectName.editText?.text.toString())) {
                Log.e(TAG, "Name is not unique, show Overwrite Dialog!")
                showOverwriteDialog()
            }
            else
            {
                Log.d(TAG, "Name is unique")
            }

            setScreen(notesAndCreditsScreen)
            notesAndCreditsScreen = true
        } else {
            setNextButtonEnabled(false)
            setShowProgressBar(true)
            showSelectTagsDialog()
        }
    }

    private fun checkCodeForApiKey() {
        xmlFile = File(project.directory, Constants.CODE_XML_FILE_NAME)

        try {
            xml = Files.asCharSource(xmlFile, Charsets.UTF_8).read()
            backUpXml = xml
        } catch (exception: IOException) {
            Log.e(TAG, Log.getStackTraceString(exception))
        }

        xml.findAnyOf(
            listOf(
                WEB_REQUEST_BRICK, BACKGROUND_REQUEST_BRICK,
                LOOK_REQUEST_BRICK, OPEN_URL_BRICK
            )
        )?.let {
            checkApiPattern()
        }
    }

    private fun checkApiPattern() {
        val regex = "<value>.*?((?=[A-Za-z]+[0-9]|[0-9]+[A-Za-z])[A-Za-z0-9]{24,45})"
        val apiPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
        apiMatcher = apiPattern.matcher(xml)
        if (apiMatcher.find()) {
            showApiReplacementDialog(Objects.requireNonNull(apiMatcher.group(1)))
        }
    }

    private fun apiKeyFound() {
        if (apiMatcher.find(apiMatcher.end())) {
            showApiReplacementDialog(Objects.requireNonNull(apiMatcher.group(1)))
        }
    }

    private fun replaceSecret(secret: String) {
        xml = xml.replace(secret.toRegex(), getString(R.string.api_replacement))
        try {
            val stream = FileOutputStream(xmlFile)
            stream.write(xml.toByteArray(StandardCharsets.UTF_8))
            stream.close()
        } catch (exception: IOException) {
            Log.e(TAG, Log.getStackTraceString(exception))
        }
        reloadProject()
        apiKeyFound()
    }

    private fun reloadProject() {
        try {
            projectManager.loadProject(project.directory)
            project = projectManager.currentProject
        } catch (exception: ProjectException) {
            Log.e(TAG, Log.getStackTraceString(exception))
        }
    }

    private fun loadBackup() {
        val currentName = project.name
        if (currentName != originalProjectName) {
            val toReplace = PROGRAM_NAME_START_TAG + originalProjectName + PROGRAM_NAME_END_TAG
            val replaceWith = PROGRAM_NAME_START_TAG + currentName + PROGRAM_NAME_END_TAG
            xmlFile = File(project.directory, Constants.CODE_XML_FILE_NAME)
            backUpXml = backUpXml.replace(toReplace, replaceWith)
        }
        try {
            val stream = FileOutputStream(xmlFile)
            stream.write(backUpXml.toByteArray(StandardCharsets.UTF_8))
            stream.close()
        } catch (exception: IOException) {
            Log.e(TAG, Log.getStackTraceString(exception))
        }
        reloadProject()
    }

    private fun showApiReplacementDialog(secret: String) {
        dialogReplaceApiKeyBinding = DialogReplaceApiKeyBinding.inflate(layoutInflater)
        dialogReplaceApiKeyBinding.replaceApiKeyWarning.movementMethod =
            LinkMovementMethod.getInstance()

        val warningURL = getString(
            R.string.api_replacement_dialog_warning,
            WIKI_URL
        )
        dialogReplaceApiKeyBinding.replaceApiKeyWarning.text = Html.fromHtml(warningURL)

        val alertDialog = AlertDialog.Builder(this)
            .setTitle(R.string.warning)
            .setView(dialogReplaceApiKeyBinding.root)
            .setPositiveButton(getString(R.string.api_replacement_dialog_accept)) { _, _ ->
                replaceSecret(
                    secret
                )
            }
            .setNegativeButton(getText(R.string.api_replacement_dialog_neutral)) { _, _ -> apiKeyFound() }
            .setNeutralButton(getText(R.string.cancel)) { _, _ ->
                loadBackup()
                finish()
            }
            .setCancelable(false)
            .create()

        alertDialog.show()
        dialogReplaceApiKeyBinding.replaceApiKey.text = secret
    }

    private fun showSelectTagsDialog() {
        val checkedTags: MutableList<String> = ArrayList()
        val availableTags = tags.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.upload_tag_dialog_title)
            .setMultiChoiceItems(
                availableTags,
                null
            ) { dialog: DialogInterface, indexSelected: Int, isChecked: Boolean ->
                if (isChecked) {
                    if (checkedTags.size >= Constants.MAX_NUMBER_OF_CHECKED_TAGS) {
                        ToastUtil.showError(this, R.string.upload_tags_maximum_error)
                        (dialog as AlertDialog).listView.setItemChecked(indexSelected, false)
                    } else {
                        checkedTags.add(availableTags[indexSelected])
                    }
                } else {
                    checkedTags.remove(availableTags[indexSelected])
                }
            }
            .setPositiveButton(getText(R.string.next)) { _, _ ->
                project.tags = checkedTags
                startProjectUpload()
            }
            .setNegativeButton(getText(R.string.cancel)) { dialog, which ->
                Utils.invalidateLoginTokenIfUserRestricted(this)
                setShowProgressBar(false)
                setNextButtonEnabled(true)
            }
            .setCancelable(false)
            .show()
    }

    private fun startProjectUpload() {
        Log.d(TAG, "Starting project upload process")
        showUploadDialog()
        projectUploadJob = GlobalScope.launch(Dispatchers.Main) {
            // run asynchronous
            val projectZipped = withContext(Dispatchers.Default) {
                ProjectZipper.zipProjectToArchive(
                    File(
                        project.directory.absolutePath
                    ), File(cacheDir, "upload$CATROBAT_EXTENSION")
                )
            }

            if (projectZipped == null) {
                // Maybe change error message on zipping error?
                showErrorDialog("Could not pack project to zip file")
                Log.d(TAG, "Could not pack project to zip file")
                return@launch
            }

            projectUploadTask.uploadProject(
                projectZipped,
                Utils.md5Checksum(projectZipped),
                sharedPreferences.getString(Constants.TOKEN, Constants.NO_TOKEN).orEmpty(),
            )
        }
    }

    private fun checkIfProjectNameAlreadyExists(name: String) : Boolean {
        return projectNamesOfUser.contains(name)
    }

    private fun showOverwriteDialog() {
        val view = View.inflate(this, R.layout.dialog_overwrite_project, null)
        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_group)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.input)

        val textWatcher: InputWatcher.TextWatcher = object : InputWatcher.TextWatcher() {
            override fun isNameUnique(name: String?): Boolean {
                return name?.let { checkIfProjectNameAlreadyExists(it) } ?: true
            }
        }

        val builder = TextInputDialog.Builder(this)
            .setText(binding.inputProjectName.editText?.text.toString())
            .setTextWatcher(textWatcher)
            .setPositiveButton(
                getString(R.string.ok),
                TextInputDialog.OnClickListener {dialog: DialogInterface?, textInput: String? ->
                    when (radioGroup.checkedRadioButtonId) {
                        R.id.rename -> {
                            if (textInput != null) {
                                project.name = textInput
                            }
                        }
                        R.id.replace -> {
                            Log.d(TAG, "Project will be overwritten!")
                        }
                        else -> throw java.lang.IllegalStateException("Cannot find Radio Button")
                    }
                }
            )
        val alertDialog: AlertDialog = builder
            .setTitle(R.string.overwrite_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        radioGroup.setOnCheckedChangeListener(RadioGroup.OnCheckedChangeListener {group:
        RadioGroup?, checkedId: Int ->
            when (checkedId) {
                R.id.replace -> {
                    inputLayout.visibility = TextView.GONE
                    inputLayout.editText.hideKeyboard()
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                }
                R.id.rename -> {
                    inputLayout.visibility = TextView.VISIBLE
                    inputLayout.editText.showKeyboard()
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = textWatcher
                        .validateInput(inputLayout.editText!!.text.toString(), this) == null
                }
            }
        })
        alertDialog.show()
    }

    private fun setScreen(screen: Boolean) {
        if (screen) setVisibility(View.VISIBLE) else setVisibility(View.GONE)
        binding.projectNotesAndCreditsExplanation.visibility =
            if (screen) View.GONE else View.VISIBLE
        binding.inputProjectNotesAndCredits.visibility = if (screen) View.GONE else View.VISIBLE
    }

    private fun setVisibility(visibility: Int) {
        binding.projectImageView.visibility = visibility
        binding.projectSize.visibility = visibility
        binding.projectSizeView.visibility = visibility
        binding.inputProjectName.visibility = visibility
        binding.inputProjectDescription.visibility = visibility
    }

    private fun showUploadDialog() {
        if (MainMenuActivity.surveyCampaign != null) {
            MainMenuActivity.surveyCampaign?.uploadFlag = true
        }

        uploadProgressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.upload_project_dialog_title))
            .setView(R.layout.dialog_upload_project_progress)
            .setPositiveButton(R.string.progress_upload_dialog_show_program) { _, _ ->
                loadBackup()
                projectManager.resetChangedFlag(project)
            }
            .setNegativeButton(R.string.done) { _, _ ->
                loadBackup()
                projectManager.resetChangedFlag(project)
                MainMenuActivity.surveyCampaign?.showSurvey(this)

                finish()
            }
            .setCancelable(false)
            .create()
        uploadProgressDialog?.show()
        uploadProgressDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
    }

    private fun showErrorDialog(errorMessage: String) {
        uploadProgressDialog?.findViewById<View>(R.id.dialog_upload_progress_progressbar)?.visibility =
            View.GONE
        uploadProgressDialog?.findViewById<View>(R.id.dialog_upload_message_failed)?.visibility =
            View.VISIBLE
        val image =
            uploadProgressDialog?.findViewById<ImageView>(R.id.dialog_upload_progress_image)
        image?.setImageResource(R.drawable.ic_upload_failed)
        image?.visibility = View.VISIBLE
    }

    private fun showSuccessDialog(projectMetaData: ProjectUploadResponseApi) {
        val positiveButton = uploadProgressDialog?.getButton(DialogInterface.BUTTON_POSITIVE)
        positiveButton?.setOnClickListener {
            val intent = Intent(this, WebViewActivity::class.java)
            intent.putExtra(WebViewActivity.INTENT_PARAMETER_URL, projectMetaData.project_url)
            startActivity(intent)
            loadBackup()
            projectManager.resetChangedFlag(project)
            finish()
        }

        positiveButton?.isEnabled = true
        uploadProgressDialog?.findViewById<View>(R.id.dialog_upload_progress_progressbar)?.visibility =
            View.GONE

        val image = uploadProgressDialog?.findViewById<ImageView>(R.id.dialog_upload_progress_image)
        image?.setImageResource(R.drawable.ic_upload_success)
        image?.visibility = View.VISIBLE

        val numberOfUploadedProjects = sharedPreferences.getInt(NUMBER_OF_UPLOADED_PROJECTS, 0) + 1
        sharedPreferences.edit()
            .putInt(NUMBER_OF_UPLOADED_PROJECTS, numberOfUploadedProjects)
            .apply()

        if (numberOfUploadedProjects != 2) {
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rating_dialog_title))
            .setView(R.layout.dialog_rate_pocketcode)
            .setPositiveButton(R.string.rating_dialog_rate_now) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=$packageName")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: ActivityNotFoundException) {
                    Log.e(TAG, "onReceiveResult: ", e)
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Constants.PLAY_STORE_PAGE_LINK + packageName)
                        )
                    )
                }
            }
            .setNeutralButton(getString(R.string.rating_dialog_rate_later)) { _, _ ->
                sharedPreferences
                    .edit()
                    .putInt(NUMBER_OF_UPLOADED_PROJECTS, 0)
                    .apply()
            }
            .setNegativeButton(getString(R.string.rating_dialog_rate_never), null)
            .setCancelable(false)
            .show()
    }

    protected open fun verifyUserIdentity() {
        val token = sharedPreferences.getString(Constants.TOKEN, Constants.NO_TOKEN)
        if (connectionMonitor.isNetworkAvailable()) {
            token?.let {
                if (token != Constants.NO_TOKEN) {
                    tokenTask.isValidToken().observe(this, Observer { isValid ->
                        if (isValid) {
                            onCreateView()
                        } else {
                            val refreshToken = sharedPreferences.getString(
                                Constants.REFRESH_TOKEN,
                                Constants.NO_TOKEN
                            ).orEmpty()

                            when {
                                token.length == DEPRECATED_TOKEN_LENGTH -> checkRefreshToken(
                                    token,
                                    refreshToken
                                )
                                refreshToken != Constants.NO_TOKEN -> checkRefreshToken(
                                    token,
                                    refreshToken
                                )
                                else -> verifyUserIdentityFailed()
                            }
                        }
                    })
                    tokenTask.checkToken(token)
                    return
                }
            }
            startSignInWorkflow()
        } else {
            ToastUtil.showError(this, R.string.error_internet_connection)
            finish()
        }
    }

    private fun checkRefreshToken(token: String, refreshToken: String) {
        tokenTask.getRefreshTokenResponse().observe(this, Observer { refreshResponse ->
            refreshResponse?.let {
                sharedPreferences.edit()
                    .putString(Constants.TOKEN, refreshResponse.token)
                    .putString(Constants.REFRESH_TOKEN, refreshResponse.refresh_token)
                    .apply()
                onCreateView()
            } ?: run {
                verifyUserIdentityFailed()
            }
        })

        tokenTask.refreshToken(token, refreshToken)
    }

    @Deprecated("Use new API call instead", ReplaceWith("checkRefreshToken(token, refreshToken)"))
    private fun checkDeprecatedToken(token: String) {
        tokenTask.getUpgradeTokenResponse().observe(this, Observer { upgradeResponse ->
            upgradeResponse?.let {
                sharedPreferences.edit()
                    .putString(Constants.TOKEN, upgradeResponse.token)
                    .putString(Constants.REFRESH_TOKEN, upgradeResponse.refresh_token)
                    .apply()
                onCreateView()
            } ?: run {
                verifyUserIdentityFailed()
            }
        })

        tokenTask.upgradeToken(token)
    }

    private fun verifyUserIdentityFailed() {
        ToastUtil.showError(this, R.string.error_session_expired)
        Utils.logoutUser(this)
        startSignInWorkflow()
    }

    private fun startSignInWorkflow() {
        startActivityForResult(Intent(this, SignInActivity::class.java), SIGN_IN_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SIGN_IN_CODE) {
            if (resultCode == RESULT_OK) {
                onCreateView()
            } else {
                finish()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun getTags() {
        tagsTask.getTagsResponse().observe(this, Observer { tagsResponse ->
            tagsResponse?.let { tags ->
                this.tags = tags
            }
        })

        tagsTask.getTags()
    }

    private fun getAllUserProjects() {
        getUserProjectsJob = GlobalScope.launch(Dispatchers.Main) {
            getUserProjectsTask.getProjectsFromUser(
                sharedPreferences.getString(Constants.TOKEN, Constants.NO_TOKEN).orEmpty()
            )
        }
    }

    fun addProjectName(name: String) {
        projectNamesOfUser.add(name)
    }

    inner class NameInputTextWatcher : TextWatcher {
        fun validateName(name: String): String? {
            var name = name
            if (name.isEmpty()) {
                return getString(R.string.name_empty)
            }
            name = name.trim { it <= ' ' }
            if (name.isEmpty()) {
                return getString(R.string.name_consists_of_spaces_only)
            }
            if (name == getString(R.string.default_project_name)) {
                return getString(R.string.error_upload_project_with_default_name, name)
            }
            return if (name != project.name &&
                FileMetaDataExtractor.getProjectNames(FlavoredConstants.DEFAULT_ROOT_DIRECTORY)
                    .contains(name)
            ) {
                getString(R.string.name_already_exists)
            } else null
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) {
            val error = validateName(s.toString())
            binding.inputProjectName.error = error
            setNextButtonEnabled(error == null)
        }
    }
}

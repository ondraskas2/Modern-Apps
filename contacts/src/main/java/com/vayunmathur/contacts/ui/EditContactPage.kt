package com.vayunmathur.contacts.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.ContactDetail
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.data.formatDisplay
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate
import okio.Buffer
import kotlin.io.encoding.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContactPage(backStack: NavBackStack<Route>, viewModel: ContactViewModel, editRoute: Route.EditContact) {
    val contactId = editRoute.contactId
    val context = LocalContext.current

    // Initialize the VM draft for this contact. No-op on rotation (same key).
    LaunchedEffect(contactId) {
        viewModel.initEditDraft(
            contactId = contactId,
            prefillName = editRoute.name,
            prefillPhone = editRoute.phone,
            prefillEmail = editRoute.email,
            prefillCompany = editRoute.company,
            prefillNotes = editRoute.notes,
        )
    }
    val draft by viewModel.editDraft.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val isNewContact = contactId == null

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.setEditDraftPhotoFromUri(uri)
        }
    }

    val currentDraft = draft
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val pageTitle = if (isNewContact) stringResource(R.string.add_contact) else stringResource(R.string.edit_contact)
                    Text(pageTitle)
                },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        IconClose()
                    }
                },
                actions = {
                    Button(onClick = {
                        viewModel.saveEditDraft()
                        backStack.pop()
                    }) {
                        Text(stringResource(R.string.save))
                    }
                }
            )
        }
    ) { paddingValues ->
        if (currentDraft == null) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            AddPictureSection(
                photo = currentDraft.photo?.photo,
                viewModel = viewModel,
                onClick = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                removePhoto = {
                    viewModel.updateEditDraft { it.copy(photo = null) }
                }
            )
            Spacer(Modifier.height(24.dp))

            if (isNewContact) {
                AccountChooser(currentDraft.accountName, accounts) { name, type ->
                    viewModel.updateEditDraft { it.copy(accountName = name, accountType = type) }
                    viewModel.setLastSelectedAccount(name, type)
                }
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = currentDraft.firstName,
                onValueChange = { v -> viewModel.updateEditDraft { it.copy(firstName = v) } },
                label = { Text(stringResource(R.string.first_name)) },
                leadingIcon = {
                    NamePrefixChooser(currentDraft.namePrefix) { v ->
                        viewModel.updateEditDraft { it.copy(namePrefix = v) }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = currentDraft.middleName,
                onValueChange = { v -> viewModel.updateEditDraft { it.copy(middleName = v) } },
                label = { Text(stringResource(R.string.middle_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = currentDraft.lastName,
                onValueChange = { v -> viewModel.updateEditDraft { it.copy(lastName = v) } },
                label = { Text(stringResource(R.string.last_name)) },
                trailingIcon = {
                    NameSuffixChooser(currentDraft.nameSuffix) { v ->
                        viewModel.updateEditDraft { it.copy(nameSuffix = v) }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = currentDraft.nickname,
                onValueChange = { v -> viewModel.updateEditDraft { it.copy(nickname = v) } },
                label = { Text(stringResource(R.string.nickname)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = currentDraft.company,
                onValueChange = { v -> viewModel.updateEditDraft { it.copy(company = v) } },
                label = { Text(stringResource(R.string.company)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            DetailsSection(
                detailType = stringResource(R.string.phone),
                addLabelRes = R.string.add_phone,
                removeLabelRes = R.string.remove_phone,
                details = currentDraft.phoneNumbers,
                onDetailsChange = { list -> viewModel.updateEditDraft { it.copy(phoneNumbers = list) } },
                icon = painterResource(R.drawable.outline_call_24),
                keyboardType = KeyboardType.Phone,
                visualTransformation = VisualTransformation.None,
                options = listOf(CDKPhone.TYPE_MOBILE, CDKPhone.TYPE_HOME, CDKPhone.TYPE_WORK, CDKPhone.TYPE_OTHER)
            )
            Spacer(Modifier.height(8.dp))

            DetailsSection(
                detailType = stringResource(R.string.email),
                addLabelRes = R.string.add_email,
                removeLabelRes = R.string.remove_email,
                details = currentDraft.emails,
                onDetailsChange = { list -> viewModel.updateEditDraft { it.copy(emails = list) } },
                icon = painterResource(R.drawable.outline_mail_24),
                keyboardType = KeyboardType.Email,
                visualTransformation = VisualTransformation.None,
                options = listOf(CDKEmail.TYPE_HOME, CDKEmail.TYPE_WORK, CDKEmail.TYPE_OTHER, CDKEmail.TYPE_MOBILE)
            )

            Spacer(Modifier.height(16.dp))

            Birthday(backStack, currentDraft.birthday) { v ->
                viewModel.updateEditDraft { it.copy(birthday = v) }
            }

            DateDetailsSection(
                backStack = backStack,
                details = currentDraft.dates,
                onDetailsChange = { list -> viewModel.updateEditDraft { it.copy(dates = list) } },
                icon = painterResource(R.drawable.outline_event_24),
                options = listOf(CDKEvent.TYPE_ANNIVERSARY, CDKEvent.TYPE_OTHER)
            )

            Spacer(Modifier.height(12.dp))

            DetailsSection(
                detailType = stringResource(R.string.addresses),
                addLabelRes = R.string.add_address,
                removeLabelRes = R.string.remove_address,
                details = currentDraft.addresses,
                onDetailsChange = { list -> viewModel.updateEditDraft { it.copy(addresses = list) } },
                icon = painterResource(R.drawable.outline_event_24),
                keyboardType = KeyboardType.Text,
                visualTransformation = VisualTransformation.None,
                options = listOf(CDKStructuredPostal.TYPE_HOME, CDKStructuredPostal.TYPE_WORK, CDKStructuredPostal.TYPE_OTHER)
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = currentDraft.noteContent,
                onValueChange = { v -> viewModel.updateEditDraft { it.copy(noteContent = v) } },
                label = { Text(stringResource(R.string.note)) },
                leadingIcon = {
                    IconEdit()
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun AccountChooser(
    accountName: String,
    accounts: List<ContactAccount>,
    onAccountChange: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val onDevice = stringResource(R.string.on_device)
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = accountName.ifEmpty { onDevice },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.account)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(painterResource(R.drawable.baseline_arrow_drop_down_24), stringResource(R.string.choose_account))
                }
            }
        )
        DropdownMenu(expanded, { expanded = false }) {
            if (accounts.none { it.name.isBlank() && it.type.isBlank() }) {
                DropdownMenuItem(
                    text = { Text(onDevice) },
                    onClick = {
                        onAccountChange("", "")
                        expanded = false
                    }
                )
            }
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_display_format, account.name.ifEmpty { onDevice }, account.type)) },
                    onClick = {
                        onAccountChange(account.name, account.type)
                        expanded = false
                    }
                )
            }
        }
        Box(Modifier.matchParentSize().clickable { expanded = true })
    }
}

private fun getCountryFlagEmoji(phoneNumber: String): String {
    val phoneUtil = PhoneNumberUtil.getInstance()
    return try {
        val numberProto = phoneUtil.parse(phoneNumber, "")
        val regionCode = phoneUtil.getRegionCodeForNumber(numberProto)
        val firstLetter = Character.codePointAt(regionCode, 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(regionCode, 1) - 0x41 + 0x1F1E6
        String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    } catch (_: Exception) {
        ""
    }
}

val namePrefixes = listOf("None", "Dr", "Mr", "Mrs", "Ms")
val nameSuffixes = listOf("None", "Jr", "Sr", "I", "II", "III", "IV", "V")

@Composable
fun NamePrefixChooser(namePrefix: String, onNamePrefixChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text(namePrefix)
        Icon(
            painterResource(R.drawable.baseline_arrow_drop_down_24),
            contentDescription = null
        )
        DropdownMenu(expanded, { expanded = false }) {
            namePrefixes.forEach { prefix ->
                DropdownMenuItem(text = { Text(prefix) }, onClick = {
                    onNamePrefixChange(if(prefix == "None") "" else prefix)
                    expanded = false
                })
            }
        }
    }
}

@Composable
fun NameSuffixChooser(nameSuffix: String, onNameSuffixChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text(nameSuffix)
        Icon(painterResource(R.drawable.baseline_arrow_drop_down_24), null)
        DropdownMenu(expanded, { expanded = false }) {
            nameSuffixes.forEach { suffix ->
                DropdownMenuItem(text = { Text(suffix) }, onClick = {
                    onNameSuffixChange(suffix)
                    expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Birthday(
    backStack: NavBackStack<Route>,
    birthday: LocalDate?,
    setBirthday: (LocalDate?) -> Unit
) {
    ResultEffect<LocalDate>("birthday") {
        setBirthday(it)
    }
    Box {
        OutlinedTextField(
            value = birthday?.formatDisplay() ?: "",
            onValueChange = { },
            readOnly = true,
            label = {Text(stringResource(R.string.birthday))},
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { setBirthday(null) }) {
                    Icon(
                        painterResource(R.drawable.baseline_remove_circle_outline_24),
                        stringResource(R.string.remove_birthday)
                    )
                }
            }
        )
        Box(
            modifier = Modifier
                .matchParentSize()
        ) {
            Box(Modifier.fillMaxWidth(0.9f).fillMaxHeight()
                .clickable { backStack.add(Route.EventDatePickerDialog("birthday",birthday)) }){}
        }
    }

    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.DateDetailsSection(
    backStack: NavBackStack<Route>,
    details: List<Event>,
    onDetailsChange: (List<Event>) -> Unit,
    icon: Painter,
    options: List<Int>
) {
    val detailType = stringResource(R.string.dates)
    val context = LocalContext.current
    details.forEachIndexed { index, detail ->
        if(detail.type == CDKEvent.TYPE_BIRTHDAY) return@forEachIndexed
        Box {
            ResultEffect<LocalDate>(detail.id.toString()) { newDate ->
                onDetailsChange(details.toMutableList().also { list -> list[index] = detail.withValue(newDate.toString()) })
            }
            OutlinedTextField(
                value = detail.startDate.formatDisplay(),
                onValueChange = { },
                readOnly = true,
                label = { Text(detailType) },
                trailingIcon = {
                    Row {
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        TextButton({ dropdownExpanded = true }) {
                            Text(detail.typeString(context))
                            Icon(
                                painterResource(R.drawable.baseline_arrow_drop_down_24),
                                contentDescription = null
                            )
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }) {
                            options.forEach { option ->
                                DropdownMenuItem(
                                    onClick = {
                                        onDetailsChange(details.toMutableList().also { it[index] = detail.withType(option) })
                                        dropdownExpanded = false
                                    },
                                    text = { Text(ContactDetail.default<Event>().withType(option).typeString(context)) }
                                )
                            }
                        }
                        IconButton(onClick = {
                            onDetailsChange(details.toMutableList().also { it.removeAt(index) })
                        }) {
                            Icon(
                                painterResource(R.drawable.baseline_remove_circle_outline_24),
                                stringResource(R.string.remove_date)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
            ) {
                Box(Modifier.fillMaxWidth(0.6f).fillMaxHeight()
                    .clickable { backStack.add(Route.EventDatePickerDialog(detail.id.toString(),detail.startDate)) }) {}
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (details.none { it.type != CDKEvent.TYPE_BIRTHDAY }) {
        FilledTonalButton(
            onClick = { onDetailsChange(details + ContactDetail.default<Event>()) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_date))
        }
    } else {
        TextButton(
            onClick = { onDetailsChange(details + ContactDetail.default<Event>()) },
            modifier = Modifier.align(Alignment.Start)
        ) {
            Text(stringResource(R.string.add_date))
        }
    }
}

@Composable
private inline fun <reified T : ContactDetail<T>> ColumnScope.DetailsSection(
    detailType: String,
    addLabelRes: Int,
    removeLabelRes: Int,
    details: List<T>,
    noinline onDetailsChange: (List<T>) -> Unit,
    icon: Painter,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation,
    options: List<Int>
) {
    val context = LocalContext.current
    details.forEachIndexed { index, detail ->
        OutlinedTextField(
            value = detail.value,
            onValueChange = { newNumber ->
                onDetailsChange(details.toMutableList().also { it[index] = detail.withValue(newNumber) })
            },
            visualTransformation = visualTransformation,
            label = { Text(detailType) },
            leadingIcon = {
                if (detail is PhoneNumber) {
                    Text(getCountryFlagEmoji(detail.value))
                }
            },
            trailingIcon = {
                Row {
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    TextButton({ dropdownExpanded = true }) {
                        Text(detail.typeString(context))
                        Icon(
                            painterResource(R.drawable.baseline_arrow_drop_down_24),
                            contentDescription = null
                        )
                    }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                onClick = {
                                    onDetailsChange(details.toMutableList().also { it[index] = detail.withType(option) })
                                    dropdownExpanded = false
                                },
                                text = { Text(ContactDetail.default<T>().withType(option).typeString(context)) }
                            )
                        }
                    }
                    IconButton(onClick = {
                        onDetailsChange(details.toMutableList().also { it.removeAt(index) })
                    }) {
                        Icon(
                            painterResource(R.drawable.baseline_remove_circle_outline_24),
                            stringResource(removeLabelRes)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
    }
    if(details.isEmpty()) {
        FilledTonalButton(
            onClick = { onDetailsChange(details + ContactDetail.default<T>()) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(addLabelRes))
        }
    } else {
        TextButton(
            onClick = { onDetailsChange(details + ContactDetail.default<T>()) },
            modifier = Modifier.align(Alignment.Start)
        ) {
            Text(stringResource(addLabelRes))
        }
    }
}

@Composable
private fun AddPictureSection(
    photo: String?,
    viewModel: ContactViewModel,
    onClick: () -> Unit,
    removePhoto: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (photo != null) {
                // Decode once via the VM cache, not on every recomposition.
                val bitmap = remember(photo) { viewModel.decodePhoto(photo) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.contact_photo),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                Icon(
                    painterResource(R.drawable.outline_add_photo_alternate_24),
                    contentDescription = stringResource(R.string.add_picture),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            val pictureLabel = if (photo != null) stringResource(R.string.change_picture) else stringResource(R.string.add_picture)
            TextButton(onClick) {
                Text(
                    text = pictureLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (photo != null) {
                TextButton(removePhoto) {
                    Text(
                        text = stringResource(R.string.remove_picture),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

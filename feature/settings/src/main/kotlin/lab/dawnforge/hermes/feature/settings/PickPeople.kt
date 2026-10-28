package lab.dawnforge.hermes.feature.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.Contacts
import android.provider.ContactsPickerSessionContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.ContextCompat
import lab.dawnforge.hermes.core.data.phonedata.phone.PeopleBook

/**
 * Picks people to tell Hermes about, at most the number given. On Android 17 and later, the system
 * contact picker: several at once, sharing only their fields, with no permission. Earlier, the
 * classic picker's one contact, which the app can read only with contacts access.
 */
internal class PickPeople : ActivityResultContract<Int, Uri?>() {
    override fun createIntent(context: Context, input: Int): Intent = if (hasSystemPicker) {
        Intent(ContactsPickerSessionContract.ACTION_PICK_CONTACTS)
            .putExtra(Intent.EXTRA_USE_SYSTEM_CONTACTS_PICKER, true)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(
                ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT,
                input.coerceIn(1, SYSTEM_PICKER_MAX),
            )
            .putExtra(ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_MATCH_ALL_DATA_FIELDS, false)
            .putStringArrayListExtra(
                ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
                PeopleBook.PICKED_FIELDS,
            )
    } else {
        Intent(Intent.ACTION_PICK, Contacts.CONTENT_URI)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }

    companion object {
        private val hasSystemPicker = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN

        /** The most the system picker takes in one go: it closes at once when asked for more. */
        private const val SYSTEM_PICKER_MAX = 100

        /** Contacts access the classic picker's contact needs before the app can read it. */
        const val PERMISSION = Manifest.permission.READ_CONTACTS

        fun needsPermission(context: Context) = !hasSystemPicker &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED
    }
}

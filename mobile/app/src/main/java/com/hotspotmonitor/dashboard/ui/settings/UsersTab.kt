package com.hotspotmonitor.dashboard.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hotspotmonitor.dashboard.AppGraph
import com.hotspotmonitor.dashboard.data.SessionState
import com.hotspotmonitor.dashboard.data.UserInfo
import com.hotspotmonitor.dashboard.data.formatDate
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.components.EmptyState
import com.hotspotmonitor.dashboard.ui.components.Loading
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ReauthDialog
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Minimum password length (Server MIN_PASSWORD_LENGTH). */
const val MIN_PASSWORD = 10

/** Server USERNAME_RE. */
private val USERNAME_RE = Regex("^[A-Za-z0-9_.@-]{3,64}$")

private fun roleName(role: String) = if (role == "admin") "an admin" else "a viewer"

/** A user change waiting for the admin's password. */
sealed interface UserAction {
    data class Create(val username: String, val password: String, val role: String) : UserAction
    data class SetRole(val user: UserInfo, val role: String) : UserAction
    data class ResetPassword(val user: UserInfo) : UserAction
    data class ResetTotp(val user: UserInfo) : UserAction
    data class Delete(val user: UserInfo) : UserAction
}

@Stable
class UsersState {
    var users by mutableStateOf<List<UserInfo>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // add-a-user form
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var showPassword by mutableStateOf(false)
    var role by mutableStateOf("viewer")
    var touched by mutableStateOf(false)

    var pending by mutableStateOf<UserAction?>(null)
    var newPassword by mutableStateOf("")
    private var job: Job? = null

    val usernameError: String? get() = if (USERNAME_RE.matches(username)) null else "3 to 64 letters, digits, dot, dash, @ or underscore."
    val passwordError: String? get() = if (password.length < MIN_PASSWORD) "At least $MIN_PASSWORD characters." else null

    fun load(g: AppGraph, scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            loading = true
            try {
                users = g.api.users()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                loading = false
            }
        }
    }

    fun submitCreate() {
        touched = true
        if (usernameError == null && passwordError == null) pending = UserAction.Create(username.trim(), password, role)
    }

    fun open(a: UserAction) {
        newPassword = ""
        pending = a
    }

    private fun replace(u: UserInfo) {
        users = users?.map { if (it.id == u.id) u else it }
    }

    /** Runs [pending] with the admin's own password; throws to keep the dialog open. */
    suspend fun confirm(g: AppGraph, ctx: Context, reauth: String) {
        val msg = when (val p = pending ?: return) {
            is UserAction.Create -> {
                val u = g.api.createUser(p.username, p.password, p.role, reauth)
                users = (users ?: emptyList()) + u
                username = ""
                password = ""
                role = "viewer"
                touched = false
                "User ${u.username} created. They set up two-factor authentication when they first sign in."
            }
            is UserAction.SetRole -> {
                replace(g.api.updateUser(p.user.id, role = p.role, reauthPassword = reauth))
                "${p.user.username} is now ${roleName(p.role)}."
            }
            is UserAction.ResetPassword -> {
                if (newPassword.length < MIN_PASSWORD) throw IllegalArgumentException("The new password needs at least $MIN_PASSWORD characters.")
                g.api.updateUser(p.user.id, password = newPassword, reauthPassword = reauth)
                "Password for ${p.user.username} changed. They are signed out everywhere."
            }
            is UserAction.ResetTotp -> {
                replace(g.api.updateUser(p.user.id, resetTotp = true, reauthPassword = reauth))
                "Two-factor authentication for ${p.user.username} was reset."
            }
            is UserAction.Delete -> {
                g.api.deleteUser(p.user.id, reauth)
                users = users?.filter { it.id != p.user.id }
                "${p.user.username} deleted."
            }
        }
        pending = null
        newPassword = ""
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
}

internal fun LazyListScope.usersItems(s: UsersState, session: SessionState, onAccount: () -> Unit) {
    val canControl = session.canControl
    item(key = "users-add") { AddUserCard(s, canControl) }
    item(key = "users-list") {
        val list = s.users.orEmpty()
        val admins = list.count { it.isAdmin }
        SectionCard(title = if (list.isEmpty()) "Users" else "Users (${list.size})") {
            when {
                s.loading && list.isEmpty() -> Loading()
                s.error != null && list.isEmpty() -> Notice(Tone.DANGER, "Couldn't load the users", s.error)
                list.isEmpty() -> EmptyState("No users.")
                else -> {
                    if (s.error != null) {
                        Notice(Tone.DANGER, null, s.error)
                        Spacer(Modifier.height(8.dp))
                    }
                    list.forEachIndexed { i, u ->
                        if (i > 0) HorizontalDivider(color = hsm.border.copy(alpha = 0.6f), modifier = Modifier.padding(start = 52.dp))
                        UserRow(
                            u = u,
                            self = u.id == session.user?.id,
                            lastAdmin = u.isAdmin && admins <= 1,
                            canControl = canControl,
                            onAction = { s.open(it) },
                            onAccount = onAccount,
                        )
                    }
                }
            }
        }
    }
    item(key = "users-note") {
        Text(
            "Every change asks for your own password again. Your own password and authenticator are changed under Account.",
            style = MaterialTheme.typography.bodySmall,
            color = hsm.faint,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun AddUserCard(s: UsersState, canControl: Boolean) {
    SectionCard(title = "Add a user") {
        Text(
            "Admins monitor and control. Viewers only monitor: no controls, root shell or messages.",
            style = MaterialTheme.typography.bodyMedium,
            color = hsm.muted,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = s.username,
            onValueChange = { s.username = it.trim() },
            label = { Text("Username") },
            singleLine = true,
            enabled = canControl,
            isError = s.touched && s.usernameError != null,
            supportingText = if (s.touched && s.usernameError != null) ({ Text(s.usernameError!!) }) else null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.password,
            onValueChange = { s.password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = canControl,
            isError = s.touched && s.passwordError != null,
            supportingText = { Text(if (s.touched && s.passwordError != null) s.passwordError!! else "At least $MIN_PASSWORD characters.") },
            visualTransformation = if (s.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { s.showPassword = !s.showPassword }) {
                    Icon(if (s.showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (s.showPassword) "Hide password" else "Show password")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("Role", style = MaterialTheme.typography.labelMedium, color = hsm.muted)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val roles = listOf("viewer" to "Viewer", "admin" to "Admin")
            roles.forEachIndexed { i, (r, label) ->
                SegmentedButton(
                    selected = s.role == r,
                    onClick = { s.role = r },
                    enabled = canControl,
                    shape = SegmentedButtonDefaults.itemShape(i, roles.size),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = { s.submitCreate() }, enabled = canControl, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add user")
        }
    }
}

@Composable
private fun UserRow(
    u: UserInfo,
    self: Boolean,
    lastAdmin: Boolean,
    canControl: Boolean,
    onAction: (UserAction) -> Unit,
    onAccount: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(u.username, u.isAdmin)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    u.username,
                    style = MaterialTheme.typography.titleMedium,
                    color = hsm.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (self) {
                    Spacer(Modifier.width(6.dp))
                    Pill("You", Tone.NEUTRAL)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (u.isAdmin) "Admin" else "Viewer", if (u.isAdmin) Tone.INFO else Tone.NEUTRAL)
                // Required for every account: without it the user only sees the setup page.
                if (u.totpEnabled) Pill("2FA on", Tone.OK) else Pill("2FA not set up", Tone.WARN)
            }
            if (u.createdAt.isNotBlank()) {
                Text("Added ${formatDate(u.createdAt)}", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
            }
        }
        if (canControl) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions for ${u.username}", tint = hsm.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    fun pick(a: UserAction) {
                        menu = false
                        onAction(a)
                    }
                    if (self) {
                        DropdownMenuItem(
                            text = { Text("Your password and 2FA: Account") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) },
                            onClick = {
                                menu = false
                                onAccount()
                            },
                        )
                    }
                    val target = if (u.isAdmin) "viewer" else "admin"
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(if (target == "admin") "Make admin" else "Make viewer")
                                if (self) Hint("You cannot change your own role") else if (lastAdmin) Hint("The last admin stays an admin")
                            }
                        },
                        leadingIcon = { Icon(if (target == "admin") Icons.Outlined.AdminPanelSettings else Icons.Outlined.PersonOutline, null) },
                        enabled = !self && !lastAdmin,
                        onClick = { pick(UserAction.SetRole(u, target)) },
                    )
                    if (!self) {
                        DropdownMenuItem(
                            text = { Text("Reset password") },
                            leadingIcon = { Icon(Icons.Outlined.Key, null) },
                            onClick = { pick(UserAction.ResetPassword(u)) },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Reset two-factor")
                                    if (!u.totpEnabled) Hint("Not set up yet")
                                }
                            },
                            leadingIcon = { Icon(Icons.Outlined.PhonelinkLock, null) },
                            enabled = u.totpEnabled,
                            onClick = { pick(UserAction.ResetTotp(u)) },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Delete user", color = if (!self && !lastAdmin) hsm.danger else hsm.faint)
                                if (self) Hint("You cannot delete yourself") else if (lastAdmin) Hint("Cannot delete the last admin")
                            }
                        },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = if (!self && !lastAdmin) hsm.danger else hsm.faint) },
                        enabled = !self && !lastAdmin,
                        onClick = { pick(UserAction.Delete(u)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = hsm.faint)
}

private data class ActionText(val title: String, val text: String, val confirm: String, val danger: Boolean)

private fun actionText(a: UserAction): ActionText = when (a) {
    is UserAction.Create -> ActionText(
        "Add ${a.username} as ${roleName(a.role)}?",
        if (a.role == "admin") "Admins can control the phone with root access, read its messages and manage users."
        else "Viewers can monitor the phone but have no controls, root shell or messages.",
        "Add user", false,
    )
    is UserAction.SetRole -> ActionText(
        "Make ${a.user.username} ${roleName(a.role)}?",
        if (a.role == "admin") "Admins can control the phone with root access, read its messages and manage users."
        else "They keep monitoring but lose controls, the root shell, messages and user management. Their open Dashboards switch over at once.",
        "Change role", false,
    )
    is UserAction.ResetPassword -> ActionText(
        "Reset password for ${a.user.username}",
        "Sets a new password for them; tell them through a safe channel. They are signed out on every device.",
        "Set password", false,
    )
    is UserAction.ResetTotp -> ActionText(
        "Reset two-factor authentication for ${a.user.username}?",
        "Use this when their phone with the authenticator app is lost or reset. This clears ${a.user.username}'s authenticator and signs them out on every " +
            "device; their push notifications stop too. They sign in with their password and must set up two-factor authentication again.",
        "Reset 2FA", true,
    )
    is UserAction.Delete -> ActionText(
        "Delete ${a.user.username}?",
        "They are signed out and cannot sign in again. This cannot be undone.",
        "Delete user", true,
    )
}

@Composable
internal fun UsersDialogs(s: UsersState) {
    val p = s.pending ?: return
    val g = graph()
    val ctx = LocalContext.current
    val t = actionText(p)
    key(p) {
        ReauthDialog(
            title = t.title,
            text = t.text,
            confirmLabel = t.confirm,
            danger = t.danger,
            extra = if (p is UserAction.ResetPassword) {
                {
                    OutlinedTextField(
                        value = s.newPassword,
                        onValueChange = { s.newPassword = it },
                        label = { Text("New password for ${p.user.username}") },
                        supportingText = { Text("At least $MIN_PASSWORD characters.") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else null,
            onDismiss = { s.pending = null },
            onConfirm = { password, _ -> s.confirm(g, ctx, password) },
        )
    }
}

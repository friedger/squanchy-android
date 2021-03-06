package net.squanchy.settings;

import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ListView;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseUser;

import net.squanchy.BuildConfig;
import net.squanchy.R;
import net.squanchy.navigation.Navigator;
import net.squanchy.proximity.ProximityFeature;
import net.squanchy.proximity.preconditions.ProximityOptInPersister;
import net.squanchy.proximity.preconditions.ProximityPreconditions;
import net.squanchy.service.proximity.injection.ProximityService;
import net.squanchy.signin.SignInService;
import net.squanchy.support.debug.DebugPreferences;
import net.squanchy.support.lang.Optional;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import timber.log.Timber;

import static net.squanchy.google.GoogleClientId.SETTINGS_FRAGMENT;

public class SettingsFragment extends PreferenceFragment {

    private final CompositeDisposable subscriptions = new CompositeDisposable();

    private SignInService signInService;
    private ProximityFeature proximityFeature;
    private Navigator navigator;
    private ProximityService proximityService;
    private ProximityPreconditions proximityPreconditions;
    private ProximityOptInPersister proximityOptInPersister;
    private DebugPreferences debugPreferences;

    private PreferenceCategory accountCategory;
    private Preference accountEmailPreference;
    private Preference accountSignInSignOutPreference;

    private SwitchPreference proximityOptInPreference;
    private Preference contestStandingsPreference;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_preferences);
        getPreferenceScreen().setOrderingAsAdded(false);

        displayBuildVersion();

        if (!BuildConfig.DEBUG) {
            removeDebugCategory();
        }

        AppCompatActivity activity = (AppCompatActivity) getActivity(); // TODO UNYOLO
        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(activity)
                .enableAutoManage(activity, SETTINGS_FRAGMENT.clientId(), connectionResult -> onGoogleConnectionFailed())
                .addApi(LocationServices.API)
                .build();

        SettingsFragmentComponent component = SettingsInjector.obtainForFragment(
                this,
                activity,
                googleApiClient,
                proximityPreconditionsCallback()
        );
        signInService = component.signInService();
        navigator = component.navigator();
        proximityService = component.proximityService();
        proximityOptInPersister = component.proximityOptInPersister();
        proximityPreconditions = component.proximityPreconditions();
        proximityFeature = component.proximityFeature();
        debugPreferences = component.debugPreferences();

        accountCategory = (PreferenceCategory) findPreference(getString(R.string.account_category_key));
        accountEmailPreference = findPreference(getString(R.string.account_email_preference_key));
        accountCategory.removePreference(accountEmailPreference);
        accountSignInSignOutPreference = findPreference(getString(R.string.account_signin_signout_preference_key));
        proximityOptInPreference = (SwitchPreference) findPreference(getString(R.string.proximity_opt_in_preference_key));
        proximityOptInPreference.setOnPreferenceChangeListener((preference, isEnabling) -> handleProximityPreferenceChange((boolean) isEnabling));

        contestStandingsPreference = findPreference(getString(R.string.contest_standings_preference_key));
        contestStandingsPreference.setOnPreferenceClickListener(preference -> {
            navigator.toContest();
            return true;
        });

        Preference aboutPreference = findPreference(getString(R.string.about_preference_key));
        aboutPreference.setOnPreferenceClickListener(preference -> {
            navigator.toAboutSquanchy();
            return true;
        });
    }

    private void onGoogleConnectionFailed() {
        Timber.e("Google Client connection failed");
        Snackbar.make(getViewOrThrow(), R.string.proximity_error_google_client_connection, Snackbar.LENGTH_LONG).show();
    }

    private ProximityPreconditions.Callback proximityPreconditionsCallback() {
        return new ProximityPreconditions.Callback() {
            @Override
            public void notOptedIn() {
                Timber.e(new IllegalStateException("Trying to enable Proximity when the user is not opted in"));
                showProximityEnablingError(R.string.proximity_error_not_opted_in);
            }

            @Override
            public void permissionDenied() {
                showProximityEnablingError(R.string.proximity_error_permission_denied);
            }

            @Override
            public void locationProviderDenied() {
                showProximityEnablingError(R.string.proximity_error_location_denied);
            }

            @Override
            public void bluetoothDenied() {
                showProximityEnablingError(R.string.proximity_error_bluetooth_denied);
            }

            @Override
            public void allChecksPassed() {
                enableUi();
            }

            @Override
            public void exceptionWhileSatisfying(Throwable throwable) {
                Timber.e(throwable, "Exception occurred while checking");
                showProximityEnablingError(R.string.proximity_error_bluetooth_denied);
            }

            @Override
            public void recheckAfterActivityResult() {
                tryOptingInAgain();
            }
        };
    }

    private void showProximityEnablingError(@StringRes int snackbarMessageResId) {
        enableUi();
        Snackbar.make(getViewOrThrow(), snackbarMessageResId, Snackbar.LENGTH_LONG)
                .show();
        proximityOptInPreference.setChecked(false);
    }

    private void tryOptingInAgain() {
        enableUi();
        if (proximityOptInPreference.isChecked()) {
            optInAndEnableProximity();
        }
    }

    private void enableUi() {
        getViewOrThrow().setEnabled(true);
    }

    private void displayBuildVersion() {
        String buildVersionKey = getString(R.string.build_version_preference_key);
        Preference buildVersionPreference = findPreference(buildVersionKey);
        String buildVersion = String.format(getString(R.string.version_x), BuildConfig.VERSION_NAME);
        buildVersionPreference.setTitle(buildVersion);
    }

    private void removeDebugCategory() {
        String debugCategoryKey = getString(R.string.debug_category_preference_key);
        Preference debugCategory = findPreference(debugCategoryKey);
        getPreferenceScreen().removePreference(debugCategory);
    }

    @Override
    public void onStart() {
        super.onStart();

        proximityOptInPreference.setChecked(proximityOptInPersister.userOptedIn());

        hideDividers();

        disableProximityAndContestBasedOnFeature();

        subscriptions.add(
                signInService.currentUser()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::onUserChanged)
        );
    }

    private void hideDividers() {
        ListView list = (ListView) getViewOrThrow().findViewById(android.R.id.list);
        list.setDivider(null);
        list.setDividerHeight(0);
    }

    private void disableProximityAndContestBasedOnFeature() {
        if (debugPreferences.contestTestingEnabled()) {
            // We always show the location and contest settings when testing is enabled.
            setProximityAndContestUiEnabled(true);
            return;
        }

        subscriptions.add(
                proximityFeature.enabled()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::setProximityAndContestUiEnabled)
        );
    }

    private void setProximityAndContestUiEnabled(boolean enabled) {
        proximityOptInPreference.setSelectable(enabled);
        contestStandingsPreference.setSelectable(enabled);
    }

    private void onUserChanged(Optional<FirebaseUser> user) {
        if (user.isPresent() && !user.get().isAnonymous()) {
            onSignedInWith(user.get());
        } else {
            onSignedOut();
        }
    }

    private void onSignedInWith(FirebaseUser firebaseUser) {
        accountCategory.addPreference(accountEmailPreference);
        accountEmailPreference.setTitle(firebaseUser.getEmail());

        accountSignInSignOutPreference.setTitle(R.string.sign_out_title);
        accountSignInSignOutPreference.setOnPreferenceClickListener(
                preference -> {
                    signInService.signOut()
                            .subscribe(() ->
                                    Snackbar.make(getViewOrThrow(), R.string.settings_message_signed_out, Snackbar.LENGTH_SHORT).show()
                            );
                    return true;
                }
        );
    }

    private void onSignedOut() {
        accountCategory.removePreference(accountEmailPreference);

        accountSignInSignOutPreference.setTitle(R.string.sign_in_title);
        accountSignInSignOutPreference.setOnPreferenceClickListener(
                preference -> {
                    navigator.toSignIn();
                    return true;
                }
        );
    }

    private boolean handleProximityPreferenceChange(boolean enableProximity) {
        if (enableProximity) {
            optInAndEnableProximity();
        } else {
            disableAndOptOutFromProximity();
        }
        return true;
    }

    private void optInAndEnableProximity() {
        proximityOptInPersister.storeUserOptedIn();
        if (proximityPreconditions.needsActionToSatisfyPreconditions()) {
            disableUi();
            proximityPreconditions.startSatisfyingPreconditions();
        } else {
            proximityService.startRadar();
        }
    }

    private void disableUi() {
        getViewOrThrow().setEnabled(false);
    }

    private View getViewOrThrow() {
        View view = getView();
        if (view == null) {
            throw new IllegalStateException("You cannot access the fragment's view when it doesn't exist yet");
        }
        return view;
    }

    private void disableAndOptOutFromProximity() {
        proximityService.stopRadar();
        proximityOptInPersister.storeUserOptedOut();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        boolean handled = proximityPreconditions.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!handled) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        boolean handled = proximityPreconditions.onActivityResult(requestCode, resultCode, data);
        if (!handled) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        subscriptions.clear();
    }
}

package nl.slimmemodelkiezer.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/** Accessible local status, feedback, export and deletion. Never shows prompt contents. */
public final class MainActivity extends Activity {
    private static final int SAVE_LOGBOOK_REQUEST = 1042;
    private TextView state;
    private TextView history;
    private TextView networkState;
    private TextView technicalState;
    private int padding;
    private Switch chatgptSwitch, claudeSwitch, geminiSwitch;
    private LinearLayout root;
    private boolean setupDialogVisible = false;
    private boolean setupRequested = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        padding = (int) (20 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        // The network check is the FIRST interactive element, above all other
        // sections. A custom drawable avoids manufacturer/theme button tints.
        final float density = getResources().getDisplayMetrics().density;
        LinearLayout internetPanel = new LinearLayout(this);
        internetPanel.setOrientation(LinearLayout.VERTICAL);
        internetPanel.setPadding(Math.round(12 * density), Math.round(12 * density),
            Math.round(12 * density), Math.round(12 * density));
        internetPanel.setBackgroundColor(android.graphics.Color.WHITE);
        internetPanel.setElevation(8 * density);
        Button checkInternet = new Button(this);
        checkInternet.setText("CONTROLEER INTERNETVERBINDING");
        checkInternet.setTextColor(android.graphics.Color.WHITE);
        checkInternet.setTextSize(20);
        checkInternet.setAllCaps(false);
        checkInternet.setGravity(android.view.Gravity.CENTER);
        checkInternet.setMinHeight(Math.round(72 * density));
        android.graphics.drawable.GradientDrawable internetBackground =
            new android.graphics.drawable.GradientDrawable();
        internetBackground.setColor(android.graphics.Color.BLACK);
        internetBackground.setCornerRadius(12 * density);
        internetBackground.setStroke(Math.round(2 * density),
            android.graphics.Color.DKGRAY);
        checkInternet.setBackgroundTintList(null);
        checkInternet.setBackground(internetBackground);
        checkInternet.setContentDescription("Controleer internetverbinding, zwarte knop bovenaan het startscherm");
        checkInternet.setOnClickListener(v -> checkNetwork());
        internetPanel.addView(checkInternet, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.round(72 * density)));
        networkState = new TextView(this);
        networkState.setText("Tik op de zwarte knop om de internetverbinding te testen.");
        networkState.setTextColor(android.graphics.Color.BLACK);
        networkState.setTextSize(17);
        networkState.setPadding(0, Math.round(8 * density), 0, 0);
        internetPanel.addView(networkState);
        LinearLayout.LayoutParams internetParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        internetParams.setMargins(0, 0, 0, padding);
        root.addView(internetPanel, internetParams);
        addHeading("Slimme Modelkiezer", 25);
        checkNetwork();
        addText("Geïnstalleerde APK: " + getInstalledVersion() + " — zwarte internetknop bovenaan; modelkeuze en knopherkenning op afstand instelbaar.");
        installCrashDiagnostic();
        RemoteRules.initialize(this);
        // On first install activate the local SLIM preference automatically.
        // Keep every saved preference on compatible APK updates.
        android.content.SharedPreferences slimPrefs = getSharedPreferences("modelkiezer_settings", MODE_PRIVATE);
        if (!slimPrefs.contains("enabled")) slimPrefs.edit().putBoolean("enabled", true).apply();
        // Main-screen shortcut: remote configuration is distinct from the HTTPS diagnostic.
        addHeading("Online instellingen", 22);
        final TextView topRemoteStatus = addText(RemoteRules.status(this));
        // The homepage online-update control has a clearly visible black frame,
        // independently of Android's theme and button tint.
        int borderDp = Math.round(10 * getResources().getDisplayMetrics().density);
        LinearLayout remoteButtonFrame = new LinearLayout(this);
        remoteButtonFrame.setOrientation(LinearLayout.VERTICAL);
        remoteButtonFrame.setPadding(borderDp, borderDp, borderDp, borderDp);
        remoteButtonFrame.setBackgroundColor(android.graphics.Color.BLACK);
        remoteButtonFrame.setContentDescription("Zwarte knop voor online instellingen op de homepage.");
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        frameParams.setMargins(0, padding / 2, 0, padding / 2);
        Button refreshOnline = new Button(this);
        refreshOnline.setText("VERNIEUW ONLINE INSTELLINGEN");
        refreshOnline.setTextColor(android.graphics.Color.WHITE);
        refreshOnline.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            android.graphics.Color.BLACK));
        refreshOnline.setTextSize(20);
        refreshOnline.setAllCaps(true);
        refreshOnline.setMinHeight(Math.round(64 * getResources().getDisplayMetrics().density));
        refreshOnline.setContentDescription("Vernieuw online instellingen. Haal de nieuwste serverregels op.");
        refreshOnline.setOnClickListener(v -> {
            topRemoteStatus.setText("Nieuwe instellingen ophalen...");
            RemoteRules.refresh(this, true);
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed())
                    topRemoteStatus.setText(RemoteRules.status(this));
            }, 3500L);
            handler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed())
                    topRemoteStatus.setText(RemoteRules.status(this));
            }, 11000L);
        });
        remoteButtonFrame.addView(refreshOnline, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(remoteButtonFrame, frameParams);
        addText("Hiermee haal je de nieuwste knop- en modelregels op. Internet controleren is een andere test.");
        boolean storageOk = HistoryStore.diagnostic(this, "APP_OPENED_BUILD_MULTI28");
        addText(storageOk ? "Logboekopslag: testregel opgeslagen" : "LOGBOEKFOUT: testregel kon niet worden opgeslagen");
        addText("Open een geselecteerde AI-app en typ een vraag. De groene SLIM-knop staat linksboven buiten het uitklapmenu, ook bij één teken. Als Android de echte verzendknop herkent, komt SLIM boven die knop te staan; anders verschijnt SLIM apart boven het invoerveld. " +
                "De APK beoordeelt je vraag lokaal, probeert het beschikbare model te kiezen " +
                "en gebruikt het huidige model als de modelkiezer niet toegankelijk is. Verzenden vereist in iedere AI-app een voor Android herkenbare verzendknop. " +
                "Berichtinhoud wordt niet opgeslagen.");

        // Android's accessibility permission is the only required opt-in.
        // Avoid a second in-app switch suppressing SLIM after reinstall.
        addText("De losse groene SLIM-knop wordt automatisch actief zodra je de Android-toegankelijkheidsdienst inschakelt. Geen extra schuifje nodig.");
        addHeading("AI-apps voor de groene SLIM-knop", 20);
        addButton("Installatiehulp opnieuw openen", v -> showAppSelectionGuide());
        addText("ChatGPT staat standaard aan. Claude en Gemini zijn optioneel. " +
            "Zet alleen apps aan die je gebruikt. De Android-toegankelijkheidsmachtiging " +
            "moet op ieder toestel afzonderlijk worden ingeschakeld. " +
            "In Claude en Gemini verzendt SLIM voorlopig met het huidige model; " +
            "automatische modelkeuze voor die apps vereist aparte schermtests.");
        addBlackButton("HERSTEL GROENE SLIM-KNOP EN OPEN CHATGPT", v -> {
            // Explicit user action: re-enable both app automation and ChatGPT after
            // reinstalling, without modifying Android's accessibility permission.
            boolean saved = getSharedPreferences("modelkiezer_settings", MODE_PRIVATE)
                .edit().putBoolean("enabled", true).commit();
            SupportedAiApps.setEnabled(this, SupportedAiApps.CHATGPT, true);
            HistoryStore.diagnostic(this, "MANUAL_SLIM_RESTORE_REQUESTED");
            if (state != null && history != null) refresh();
            if (!saved) {
                Toast.makeText(this, "Automatisering opslaan mislukt.", Toast.LENGTH_LONG).show();
                return;
            }
            if (!accessibilityEnabled()) {
                Toast.makeText(this, "Zet eerst de toegankelijkheidsdienst aan.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            openProvider(SupportedAiApps.CHATGPT);
        });
        chatgptSwitch = addProviderSwitch("ChatGPT (standaard aan)", SupportedAiApps.CHATGPT);
        claudeSwitch = addProviderSwitch("Claude (optioneel)", SupportedAiApps.CLAUDE);
        geminiSwitch = addProviderSwitch("Gemini (optioneel)", SupportedAiApps.GEMINI);

        addButton("1. Toegankelijkheidsdienst inschakelen",
            v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton("2. Open ChatGPT", v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
            if (i != null) startActivity(i);
            else Toast.makeText(this, "Installeer eerst de officiële ChatGPT-app.", Toast.LENGTH_LONG).show();
        });

        addButton("Open Claude", v -> openProvider(SupportedAiApps.CLAUDE));
        addButton("Open Gemini", v -> openProvider(SupportedAiApps.GEMINI));
        state = addText("");
        addHeading("Technische foutregistratie (live op dit toestel)", 20);
        final TextView telemetryState = addText(TelemetryClient.status(this));
        addBlackButton("CONTROLEER EXTERNE LOGGING", v -> {
            if (RemoteRules.current().telemetryEndpoint.isEmpty()) {
                telemetryState.setText("Nog geen externe logserver gekoppeld. Het logboek blijft lokaal beschikbaar.");
            } else {
                HistoryStore.diagnostic(this, "REMOTE_LOG_TEST_REQUESTED");
                telemetryState.setText("Test gestart. " + TelemetryClient.status(this));
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> telemetryState.setText(TelemetryClient.status(this)), 12000L);
            }
        });
        addText("Toont start van de dienst, herkenning van geselecteerde AI-apps en echte Android-fouten inclusief fouttype en plaats in onze code. Er wordt geen berichttekst opgeslagen.");
        technicalState = addText(HistoryStore.technicalReport(this));
        addButton("Ververs technische fouten", v -> refresh());
        addButton("Test technische foutregistratie", v -> {
            boolean saved = HistoryStore.diagnosticFailure(this, "SELF_TEST_EXCEPTION",
                new IllegalStateException("Only a diagnostic test"));
            refresh();
            Toast.makeText(this, saved ? "Testfout opgeslagen; zie technische foutregistratie." : "FOUT: test kon niet worden opgeslagen.", Toast.LENGTH_LONG).show();
        });
        addText("Deze HTTPS-test stuurt geen berichten of accountgegevens en is geen rechtstreekse ChatGPT-koppeling.");
        addHeading("Online regels (gratis)", 20);
        addText("Online regels zijn al gekoppeld. Voor normaal gebruik hoef je hier niets in te stellen. Geavanceerd: hieronder kun je de publieke configuratie-URL aanpassen. Berichten worden nooit naar GitHub gestuurd. Wijzigingen aan de Android-verzendcode vereisen wel een nieuwe APK.");
        EditText configUrl = new EditText(this);
        configUrl.setSingleLine(false);
        configUrl.setMinLines(2);
        configUrl.setHint("https://raw.githubusercontent.com/gebruikersnaam/openbare-repo/main/config.json");
        configUrl.setText(RemoteRules.getUrl(this));
        configUrl.setContentDescription("Publieke GitHub configuratie URL");
        root.addView(configUrl);
        TextView remoteStatus = addText(RemoteRules.status(this));
        addButton("Koppel URL en laad regels", v -> {
            if (!RemoteRules.setUrl(this, configUrl.getText().toString())) {
                Toast.makeText(this, "Gebruik een geldige HTTPS raw.githubusercontent.com of gist.githubusercontent.com URL.", Toast.LENGTH_LONG).show();
                return;
            }
            RemoteRules.refresh(this, true);
            remoteStatus.setText("Ophalen gestart. Tik straks op Controleer regels.");
        });
        addButton("Controleer regels", v -> {
            remoteStatus.setText("Online regels ophalen...");
            RemoteRules.refresh(this, true);
            // Download is asynchronous; never claim success before its status is saved.
            android.os.Handler statusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            statusHandler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) remoteStatus.setText(RemoteRules.status(this));
            }, 3500L);
            statusHandler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) remoteStatus.setText(RemoteRules.status(this));
            }, 11000L);
        });
        addHeading("Controle van modelkeuzes", 20);
        addText("Hier zie je welke selectie de APK op het scherm kon bevestigen, de extra wachttijd " +
                "en eventuele fouten. De APK kan niet controleren of de server een antwoord heeft verwerkt.");
        history = addText("");
        addButton("Ververs resultaten", v -> refresh());
        addText("LET OP: zet de schakelaar en de toegankelijkheidsdienst aan. Als de normale verzendknop herkend wordt, onderschept de groene SLIM-knop de verzendtik. Anders gebruik je de losse groene SLIM-knop; als het modelmenu niet toegankelijk is, wordt met het huidige model verzonden zodra de echte verzendknop herkend is.");
        addButton("Test logboekopslag", v -> {
            boolean written = HistoryStore.diagnostic(this, "STORAGE_TEST");
            refresh();
            Toast.makeText(this, written
                ? "Testregel opgeslagen. Exporteer nu het logboek."
                : "Opslag mislukt. Logboek kan niet worden geschreven.", Toast.LENGTH_LONG).show();
        });
        addButton("Laatste modelkeuze was juist", v -> {
            HistoryStore.feedback(this, true); refresh();
        });
        addButton("Laatste modelkeuze was onjuist", v -> {
            HistoryStore.feedback(this, false); refresh();
        });
        addText("Je beoordeling helpt de APK een eerder succesvolle modelnaam te onthouden. " +
                "Bij een afgekeurde keuze wordt die naam niet opnieuw als voorkeur gebruikt. " +
                "De inhoud van je bericht is hiervoor niet nodig.");
        addBlackButton("EXPORTEER LOGBOEK NAAR DOWNLOADS", v -> saveLogbook());
        addButton("Deel logboek als bestand", v -> shareLogbook());
        addButton("Kopieer logboek (als delen niet werkt)", v -> copyLogbook());
        addButton("Verwijder alle loggegevens", v -> new AlertDialog.Builder(this)
            .setTitle("Logboek verwijderen?")
            .setMessage("Dit verwijdert alle opgeslagen modelkeuzes, timing en feedback op dit toestel.")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Verwijderen", (dialog, which) -> {
                HistoryStore.clear(this); refresh();
            }).show());
        addText("Privacy: alleen maximaal 100 technische pogingen, lokaal op dit toestel. " +
                "Geen vragen, API-sleutels of accountgegevens. Alles kan worden verwijderd.");
        setContentView(scroll);
        // ChatGPT is enabled by default; do not force the app-selection wizard.
        // Android alone must grant the accessibility permission once after clean installation.
        if (!getSharedPreferences("first_run", MODE_PRIVATE).getBoolean("setup_complete", false)) {
            root.post(this::showSetupIfNeeded);
        }
    }


    /** Guided first-run setup. User picks apps; Android permission remains explicitly opt-in. */
    private void showAppSelectionGuide() {
        final String[] names = {"ChatGPT (standaard)", "Claude", "Gemini"};
        final String[] pkgs = {SupportedAiApps.CHATGPT, SupportedAiApps.CLAUDE, SupportedAiApps.GEMINI};
        final boolean[] selected = {
            SupportedAiApps.enabled(this, pkgs[0]),
            SupportedAiApps.enabled(this, pkgs[1]),
            SupportedAiApps.enabled(this, pkgs[2])
        };
        new AlertDialog.Builder(this)
            .setTitle("Stap 1 van 2: kies je AI-apps")
            .setMessage("Kies in welke apps je de groene SLIM-knop wilt gebruiken. ChatGPT staat standaard aan. Je kunt dit later wijzigen.")
            .setMultiChoiceItems(names, selected, (dialog, which, checked) -> selected[which] = checked)
            .setPositiveButton("Volgende", (dialog, which) -> {
                if (!selected[0] && !selected[1] && !selected[2]) {
                    Toast.makeText(this, "Kies ten minste één AI-app.", Toast.LENGTH_LONG).show();
                    showAppSelectionGuide();
                    return;
                }
                for (int i = 0; i < pkgs.length; i++) SupportedAiApps.setEnabled(this, pkgs[i], selected[i]);
                if (chatgptSwitch != null) chatgptSwitch.setChecked(selected[0]);
                if (claudeSwitch != null) claudeSwitch.setChecked(selected[1]);
                if (geminiSwitch != null) geminiSwitch.setChecked(selected[2]);
                getSharedPreferences("first_run", MODE_PRIVATE).edit().putBoolean("setup_complete", false).apply();
                showSetupIfNeeded();
            })
            .setNegativeButton("Later", null)
            .show();
    }

    /** Launch a guided, opt-in first-run setup. An accessibility permission cannot be granted in-app. */
    private boolean accessibilityEnabled() {
        android.view.accessibility.AccessibilityManager manager =
            (android.view.accessibility.AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        for (android.accessibilityservice.AccessibilityServiceInfo service :
                manager.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            android.content.pm.ServiceInfo info = service.getResolveInfo().serviceInfo;
            if (info != null && getPackageName().equals(info.packageName)
                    && ChatGptAccessibilityService.class.getName().equals(info.name)) return true;
        }
        return false;
    }

    private void showSetupIfNeeded() {
        if (isFinishing() || setupDialogVisible ||
                getSharedPreferences("first_run", MODE_PRIVATE).getBoolean("setup_complete", false)) return;
        boolean access = accessibilityEnabled();
        if (access) {
            // Android permission alone starts the native SLIM overlay.
            getSharedPreferences("modelkiezer_settings", MODE_PRIVATE)
                .edit().putBoolean("enabled", true).apply();
            getSharedPreferences("first_run", MODE_PRIVATE).edit()
                .putBoolean("setup_complete", true).apply();
            // No extra completion prompt: the separate SLIM overlay is ready.
            return;
        }
        setupDialogVisible = true;
        String message = "Eenmalig: open Android Toegankelijkheid en schakel Slimme Modelkiezer in bij Geïnstalleerde apps. Bevestig de Android-waarschuwing zelf. Het SLIM-schuifje staat in de app al aan.\n\n"            + "Is de dienst grijs? Open Android Instellingen > Apps > Slimme Modelkiezer > menu rechtsboven > Beperkte instellingen toestaan, indien beschikbaar.";
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("Slimme Modelkiezer instellen")
            .setMessage(message)
            .setCancelable(false);
        b.setPositiveButton("Open toegankelijkheid", (d, w) -> {
                setupDialogVisible = false;
                setupRequested = true;
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                catch (Exception e) {
                    Toast.makeText(this, "Open Instellingen > Toegankelijkheid en schakel Slimme Modelkiezer in.", Toast.LENGTH_LONG).show();
                }
            });
        b.setNegativeButton("Later", (d, w) -> setupDialogVisible = false);
        b.show();
    }

    /** Each export must contain a fresh marker. An empty file must never look successful. */
    private void installCrashDiagnostic() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof AppCrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new AppCrashHandler(this, previous));
    }

    private static final class AppCrashHandler implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler previous;
        AppCrashHandler(Context context, Thread.UncaughtExceptionHandler previous) {
            this.context = context.getApplicationContext();
            this.previous = previous;
        }
        @Override public void uncaughtException(Thread thread, Throwable error) {
            HistoryStore.diagnosticFailure(context, "UNCAUGHT_APP_EXCEPTION", error);
            if (previous != null) previous.uncaughtException(thread, error);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(1); }
        }
    }

    private boolean recordExportTest() {
        if (!HistoryStore.diagnostic(this, "EXPORT_BUILD_REMOTE26") ||
                "[]".equals(HistoryStore.json(this))) {
            new AlertDialog.Builder(this)
                .setTitle("Logboekopslag mislukt")
                .setMessage("Deze APK kan geen logboek opslaan. Controleer de geïnstalleerde versie bovenaan het startscherm.")
                .setPositiveButton("OK", null).show();
            return false;
        }
        refresh();
        return true;
    }

    /** Share an actual JSON attachment, not pasted log text. The user chooses the recipient. */
    private void shareLogbook() {
        if (!recordExportTest()) return;
        try {
            File directory = new File(getCacheDir(), "gedeelde_logboeken");
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new java.io.IOException("Kan de tijdelijke map niet maken.");
            File file = new File(directory, "slimme-modelkiezer-logboek.json");
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(HistoryStore.json(this).getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/json");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.setClipData(ClipData.newUri(getContentResolver(), "Technisch logboek", uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(share, "Deel het JSON-logboek met ChatGPT");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(this, "Delen mislukt. Gebruik Exporteer naar Downloads of Kopieer logboek.", Toast.LENGTH_LONG).show();
        }
    }

    /** Android's built-in document picker works even when no application accepts shared JSON. */
    private void saveLogbook() {
        if (!recordExportTest()) return;
        try {
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            save.addCategory(Intent.CATEGORY_OPENABLE);
            save.setType("application/json");
            save.putExtra(Intent.EXTRA_TITLE, "slimme-modelkiezer-logboek.json");
            startActivityForResult(save, SAVE_LOGBOOK_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "Bestanden openen lukt niet. Gebruik Kopieer logboek.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_LOGBOOK_REQUEST || resultCode != RESULT_OK) return;
        Uri destination = data == null ? null : data.getData();
        if (destination == null) {
            Toast.makeText(this, "Geen bestand gekozen.", Toast.LENGTH_LONG).show();
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
            if (out == null) throw new java.io.IOException("Kan bestand niet openen");
            out.write(HistoryStore.json(this).getBytes(StandardCharsets.UTF_8));
            out.flush();
            Toast.makeText(this, "Logboek opgeslagen. Voeg dit bestand toe in ChatGPT.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Opslaan mislukt. Gebruik Kopieer logboek.", Toast.LENGTH_LONG).show();
        }
    }

    private void copyLogbook() {
        if (!recordExportTest()) return;
        try {
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("Geen klembord");
            clipboard.setPrimaryClip(ClipData.newPlainText(
                "Slimme Modelkiezer technisch logboek", HistoryStore.json(this)));
            Toast.makeText(this, "Logboek gekopieerd. Plak het in ChatGPT.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Kopiëren mislukt.", Toast.LENGTH_LONG).show();
        }
    }

    private Switch addProviderSwitch(String label, String pkg) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(18);
        toggle.setPadding(0, padding / 3, 0, padding / 3);
        toggle.setChecked(SupportedAiApps.enabled(this, pkg));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            SupportedAiApps.setEnabled(this, pkg, checked);
            HistoryStore.diagnostic(this,
                "PROVIDER_" + SupportedAiApps.displayName(pkg).toUpperCase(Locale.ROOT)
                    + (checked ? "_ENABLED" : "_DISABLED"));
        });
        root.addView(toggle);
        return toggle;
    }

    private void openProvider(String pkg) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) startActivity(intent);
        else Toast.makeText(this,
            "Installeer eerst " + SupportedAiApps.displayName(pkg) + ".", Toast.LENGTH_LONG).show();
    }

    private String getInstalledVersion() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (build " + code + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "versie onbekend";
        }
    }

    private TextView addHeading(String message, float size) {
        TextView text = addText(message);
        text.setTextSize(size);
        text.setPadding(0, padding / 2, 0, padding / 4);
        return text;
    }
    private TextView addText(String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextSize(16);
        text.setPadding(0, padding / 4, 0, padding / 4);
        root.addView(text);
        return text;
    }
    private void addButton(String message, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(message);
        button.setOnClickListener(listener);
        root.addView(button);
    }
    private void addBlackButton(String message, View.OnClickListener listener) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout frame = new LinearLayout(this);
        frame.setPadding(Math.round(8 * density), Math.round(8 * density),
            Math.round(8 * density), Math.round(8 * density));
        frame.setBackgroundColor(android.graphics.Color.BLACK);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, padding / 3, 0, padding / 3);
        Button button = new Button(this);
        button.setText(message);
        button.setTextColor(android.graphics.Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK));
        button.setMinHeight(Math.round(56 * density));
        button.setOnClickListener(listener);
        frame.addView(button, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(frame, params);
    }
    private void checkNetwork() {
        if (networkState == null) return;
        networkState.setText("HTTPS-verbinding controleren...");
        NetworkDiagnostic.checkAsync(this, (result) -> {
            if (isFinishing() || networkState == null) return;
            networkState.setText(result.userMessage);
            HistoryStore.diagnostic(this, result.logCode);
            refresh();
        });
    }

    private void refresh() {
        String services = Settings.Secure.getString(getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean running = services != null &&
            services.toLowerCase(Locale.ROOT).contains(getPackageName().toLowerCase(Locale.ROOT));
        android.content.SharedPreferences health =
            getSharedPreferences("connection_health", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long connected = health.getLong("service_connected_at", 0L);
        long event = health.getLong("chatgpt_event_at", 0L);
        String connection = connected <= 0 ? "Nog geen serviceverbinding geregistreerd"
            : "Laatste serviceverbinding: " +
                java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(connected));
        String received = event <= 0 ? "Nog geen ChatGPT-gebeurtenis ontvangen"
            : "Laatste ChatGPT-gebeurtenis: " +
                java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(event));
        boolean automationOn = getSharedPreferences("modelkiezer_settings", MODE_PRIVATE).getBoolean("enabled", false) || getPreferences(MODE_PRIVATE).getBoolean("enabled", false);
        state.setText("Automatisering: " + (automationOn ? "AAN" : "UIT") + "\n" + (running ? "Toegankelijkheidsmachtiging: ingeschakeld"
            : "Toegankelijkheidsmachtiging: niet ingeschakeld") +
            "\n" + connection + "\n" + received +
            (connected > 0 && now - connected > 86400000L
                ? "\nLet op: laatste verbinding is ouder dan 24 uur." : ""));
        history.setText(HistoryStore.summary(this));
        if (technicalState != null) technicalState.setText(HistoryStore.technicalReport(this));
        if ("[]".equals(HistoryStore.json(this))) history.setText("FOUT: logboek blijft leeg. App of opslag werkt niet zoals verwacht.\n" + history.getText());
    }
    @Override protected void onResume() {
        super.onResume();
        if (accessibilityEnabled()) getSharedPreferences("modelkiezer_settings", MODE_PRIVATE)
            .edit().putBoolean("enabled", true).apply();
        if (state != null && history != null) refresh();
        if (setupRequested) {
            setupRequested = false;
            root.post(this::showSetupIfNeeded);
        }
    }
}

import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const ResurrectApp());

/// ---------------------------------------------------------------------------
/// App shell + Material 3 theme
/// ---------------------------------------------------------------------------
class ResurrectApp extends StatelessWidget {
  const ResurrectApp({super.key});

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF21C7A8);
    return MaterialApp(
      title: 'Resurrect',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: seed),
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: seed,
          brightness: Brightness.dark,
        ),
      ),
      themeMode: ThemeMode.system,
      home: const ResurrectHomePage(),
    );
  }
}

/// ---------------------------------------------------------------------------
/// Native bridge
/// ---------------------------------------------------------------------------
class AdbService {
  static const MethodChannel _channel = MethodChannel('com.example.resurrect/adb');
  static const EventChannel _events = EventChannel(
    'com.example.resurrect/adb_events',
  );

  Stream<dynamic> get events => _events.receiveBroadcastStream();

  Future<bool> pair(int port, String code) async =>
      await _channel.invokeMethod<bool>('pair', {'port': port, 'code': code}) ??
      false;

  Future<bool> connect(int port) async =>
      await _channel.invokeMethod<bool>('connect', {'port': port}) ?? false;

  Future<bool> connectAuto() async =>
      await _channel.invokeMethod<bool>('connectAuto') ?? false;

  Future<bool> isPaired() async =>
      await _channel.invokeMethod<bool>('isPaired') ?? false;

  Future<void> disconnect() async => _channel.invokeMethod<void>('disconnect');

  Future<bool> hasOverlayPermission() async =>
      await _channel.invokeMethod<bool>('hasOverlayPermission') ?? false;

  Future<void> requestOverlayPermission() async =>
      _channel.invokeMethod<void>('requestOverlayPermission');

  Future<void> openDeveloperOptions() async =>
      _channel.invokeMethod<void>('openDeveloperOptions');

  Future<void> showFloatingWindow() async =>
      _channel.invokeMethod<void>('showFloatingWindow');

  Future<void> hideFloatingWindow() async =>
      _channel.invokeMethod<void>('hideFloatingWindow');

  /// Expands picks (raw .apk + .apks/.xapk/.apkm/.zip bundles) into the install set.
  Future<Selection> prepareSelection(List<String> paths) async {
    final map = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'prepareSelection',
      {'paths': paths},
    );
    return Selection.fromMap(Map<String, dynamic>.from(map ?? const {}));
  }

  /// Installs the resolved base + splits atomically. Returns "Success" / "Failure ...".
  Future<String> installApks(List<String> paths) async =>
      await _channel.invokeMethod<String>('installApks', {'paths': paths}) ?? '';

  /// Dumps the device crash logcat buffer (via the ADB shell) for diagnostics.
  Future<String> readCrashLog() async =>
      await _channel.invokeMethod<String>('readCrashLog') ?? '';
}

class Selection {
  const Selection({
    required this.label,
    required this.packageName,
    required this.versionName,
    required this.targetSdk,
    required this.minSdk,
    required this.totalSize,
    required this.parts,
    required this.hasNativeLibs,
    required this.resolvedPaths,
  });

  final String label;
  final String packageName;
  final String versionName;
  final int targetSdk;
  final int minSdk;
  final int totalSize;
  final int parts;
  final bool hasNativeLibs;
  final List<String> resolvedPaths;

  factory Selection.fromMap(Map<String, dynamic> m) => Selection(
    label: (m['label'] as String?) ?? 'APK',
    packageName: (m['packageName'] as String?) ?? 'unknown',
    versionName: (m['versionName'] as String?) ?? '',
    targetSdk: (m['targetSdk'] as int?) ?? 0,
    minSdk: (m['minSdk'] as int?) ?? 0,
    totalSize: (m['totalSize'] as int?) ?? 0,
    parts: (m['parts'] as int?) ?? 0,
    hasNativeLibs: (m['hasNativeLibs'] as bool?) ?? true,
    resolvedPaths: ((m['resolvedPaths'] as List?) ?? const [])
        .map((e) => e as String)
        .toList(),
  );

  String get sizeLabel {
    if (totalSize <= 0) return '';
    final mb = totalSize / (1024 * 1024);
    return mb >= 1
        ? '${mb.toStringAsFixed(1)} MB'
        : '${(totalSize / 1024).toStringAsFixed(0)} KB';
  }
}

class InstallResult {
  const InstallResult({
    required this.label,
    required this.packageName,
    required this.success,
    required this.message,
  });

  final String label;
  final String packageName;
  final bool success;
  final String message;
}

/// ---------------------------------------------------------------------------
/// Connection state
/// ---------------------------------------------------------------------------
enum ConnStatus { disconnected, pairing, connecting, connected, error }

extension on ConnStatus {
  String get label => switch (this) {
    ConnStatus.disconnected => 'Disconnected',
    ConnStatus.pairing => 'Pairing…',
    ConnStatus.connecting => 'Connecting…',
    ConnStatus.connected => 'Connected',
    ConnStatus.error => 'Error',
  };

  IconData get icon => switch (this) {
    ConnStatus.disconnected => Icons.link_off_rounded,
    ConnStatus.pairing => Icons.sync_rounded,
    ConnStatus.connecting => Icons.sync_rounded,
    ConnStatus.connected => Icons.check_circle_rounded,
    ConnStatus.error => Icons.error_rounded,
  };

  bool get busy => this == ConnStatus.pairing || this == ConnStatus.connecting;
}

/// ---------------------------------------------------------------------------
/// Home page
/// ---------------------------------------------------------------------------
class ResurrectHomePage extends StatefulWidget {
  const ResurrectHomePage({super.key});

  @override
  State<ResurrectHomePage> createState() => _ResurrectHomePageState();
}

class _ResurrectHomePageState extends State<ResurrectHomePage> {
  final AdbService _adb = AdbService();

  final TextEditingController _pairPortCtrl = TextEditingController();
  final TextEditingController _pairCodeCtrl = TextEditingController();
  final TextEditingController _connPortCtrl = TextEditingController();

  ConnStatus _status = ConnStatus.disconnected;
  StreamSubscription<dynamic>? _eventSub;

  bool _hasPaired = false;
  bool _showPairingControls = false;

  // APK feature state.
  Selection? _selection;
  bool _loadingInfo = false;
  bool _installing = false;
  final List<InstallResult> _log = <InstallResult>[];
  final GlobalKey<AnimatedListState> _logKey = GlobalKey<AnimatedListState>();

  bool get _connected => _status == ConnStatus.connected;
  bool get _showPairing => _showPairingControls || !_hasPaired;

  @override
  void initState() {
    super.initState();
    _eventSub = _adb.events.listen(_onNativeEvent, onError: (_) {});
    _loadPairedState();
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    _pairPortCtrl.dispose();
    _pairCodeCtrl.dispose();
    _connPortCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadPairedState() async {
    try {
      final paired = await _adb.isPaired();
      if (mounted) setState(() => _hasPaired = paired);
    } catch (_) {}
  }

  void _markPairedUi() {
    _hasPaired = true;
    _showPairingControls = false;
  }

  void _onNativeEvent(dynamic event) {
    if (event is Map && event['event'] == 'connected') {
      if (_status == ConnStatus.connected) return;
      setState(() {
        _status = ConnStatus.connected;
        _markPairedUi();
      });
    }
  }

  // ---- connection actions -------------------------------------------------

  Future<void> _handleConnect() async {
    final pairPort = int.tryParse(_pairPortCtrl.text.trim());
    final pairCode = _pairCodeCtrl.text.trim();
    final connPort = int.tryParse(_connPortCtrl.text.trim());

    FocusScope.of(context).unfocus();
    try {
      if (pairPort != null && pairCode.isNotEmpty) {
        setState(() => _status = ConnStatus.pairing);
        final paired = await _adb.pair(pairPort, pairCode);
        if (!paired) {
          setState(() => _status = ConnStatus.error);
          _snack('Pairing failed. Double-check the pairing port and code.');
          return;
        }
      }

      setState(() => _status = ConnStatus.connecting);
      final connected = connPort != null
          ? await _adb.connect(connPort)
          : await _adb.connectAuto();
      if (!connected) {
        setState(() => _status = ConnStatus.error);
        _snack(
          connPort != null
              ? 'Connection failed. Check the connection port.'
              : 'Auto-connect failed. Enter the connection port, or pair first.',
        );
        return;
      }

      setState(() {
        _status = ConnStatus.connected;
        _markPairedUi();
      });
    } on PlatformException catch (e) {
      setState(() => _status = ConnStatus.error);
      _snack(e.message ?? 'ADB error');
    }
  }

  Future<void> _disconnect() async {
    try {
      await _adb.disconnect();
    } catch (_) {}
    setState(() {
      _status = ConnStatus.disconnected;
      _selection = null;
    });
  }

  void _pairAgain() => setState(() => _showPairingControls = true);
  void _backToConnect() => setState(() => _showPairingControls = false);

  Future<void> _handleFloat() async {
    try {
      final granted = await _adb.hasOverlayPermission();
      if (!granted) {
        await _adb.requestOverlayPermission();
        _snack('Enable "Display over other apps", then tap Float again.');
        return;
      }
      await _adb.showFloatingWindow();
      await _adb.openDeveloperOptions();
    } on PlatformException catch (e) {
      _snack(e.message ?? 'Could not open the floating window');
    }
  }

  // ---- APK actions --------------------------------------------------------

  Future<void> _pickApk() async {
    try {
      // FileType.any so .xapk/.apks/.apkm bundles (which have no registered MIME)
      // are still selectable; allowMultiple so users can pick base + split APKs.
      final result = await FilePicker.pickFiles(
        type: FileType.any,
        allowMultiple: true,
      );
      final paths =
          result?.files.map((f) => f.path).whereType<String>().toList() ??
          const <String>[];
      if (paths.isEmpty) return;
      setState(() {
        _loadingInfo = true;
        _selection = null;
      });
      final selection = await _adb.prepareSelection(paths);
      if (mounted) {
        setState(() {
          _selection = selection;
          _loadingInfo = false;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _loadingInfo = false);
      _snack('Could not read selection: $e');
    }
  }

  Future<void> _install() async {
    final selection = _selection;
    if (selection == null) return;
    setState(() => _installing = true);
    try {
      final result = await _adb.installApks(selection.resolvedPaths);
      final success = result.contains('Success');
      _addLog(
        InstallResult(
          label: selection.label,
          packageName: selection.packageName,
          success: success,
          message: result,
        ),
      );
      _snack(
        success ? 'Resurrected ${selection.label} ✓' : 'Install failed: $result',
      );
    } on PlatformException catch (e) {
      _addLog(
        InstallResult(
          label: selection.label,
          packageName: selection.packageName,
          success: false,
          message: e.message ?? 'error',
        ),
      );
      _snack(e.message ?? 'Install failed');
    } finally {
      if (mounted) setState(() => _installing = false);
    }
  }

  void _addLog(InstallResult result) {
    _log.insert(0, result);
    _logKey.currentState?.insertItem(
      0,
      duration: const Duration(milliseconds: 350),
    );
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _showCrashLog() async {
    showDialog<void>(
      context: context,
      builder: (_) => const Center(child: CircularProgressIndicator()),
      barrierDismissible: false,
    );
    String log;
    try {
      log = await _adb.readCrashLog();
    } on PlatformException catch (e) {
      log = 'Could not read log: ${e.message}';
    }
    if (!mounted) return;
    Navigator.of(context).pop(); // dismiss the spinner

    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Crash log (logcat)'),
        content: SizedBox(
          width: double.maxFinite,
          child: SingleChildScrollView(
            child: SelectableText(
              log,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () {
              Clipboard.setData(ClipboardData(text: log));
              _snack('Crash log copied');
            },
            child: const Text('Copy'),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  // ---- build --------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Resurrect'),
        actions: [
          if (_connected) ...[
            IconButton(
              tooltip: 'View crash log',
              onPressed: _showCrashLog,
              icon: const Icon(Icons.bug_report_rounded),
            ),
            IconButton(
              tooltip: 'Disconnect',
              onPressed: _disconnect,
              icon: const Icon(Icons.logout_rounded),
            ),
          ],
        ],
      ),
      body: Column(
        children: [
          _ConnectionPanel(
            pairPortCtrl: _pairPortCtrl,
            pairCodeCtrl: _pairCodeCtrl,
            connPortCtrl: _connPortCtrl,
            status: _status,
            connected: _connected,
            hasPaired: _hasPaired,
            showPairing: _showPairing,
            onConnect: _status.busy ? null : _handleConnect,
            onDisconnect: _disconnect,
            onFloat: _status.busy ? null : _handleFloat,
            onPairAgain: _pairAgain,
            onBackToConnect: _backToConnect,
          ),
          Expanded(child: _featureArea()),
        ],
      ),
    );
  }

  Widget _featureArea() {
    if (!_connected) {
      return const _EmptyHint(
        icon: Icons.refresh_rounded,
        title: 'Not connected',
        message:
            'Connect over Wireless debugging above, then pick a legacy APK '
            '(or .xapk/.apks bundle) to install past the Android 14 '
            'low-target-SDK block.',
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FilledButton.tonalIcon(
                onPressed: _loadingInfo || _installing ? null : _pickApk,
                icon: const Icon(Icons.folder_open_rounded),
                label: const Text('Choose APK / bundle'),
              ),
              AnimatedSize(
                duration: const Duration(milliseconds: 250),
                curve: Curves.easeOut,
                alignment: Alignment.topCenter,
                child: _loadingInfo
                    ? const Padding(
                        padding: EdgeInsets.all(20),
                        child: Center(child: CircularProgressIndicator()),
                      )
                    : _selection == null
                    ? const SizedBox(width: double.infinity)
                    : _ApkCard(
                        selection: _selection!,
                        installing: _installing,
                        onInstall: _install,
                      ),
              ),
            ],
          ),
        ),
        if (_log.isNotEmpty) ...[
          const SizedBox(height: 8),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Install history',
                style: Theme.of(context).textTheme.titleSmall,
              ),
            ),
          ),
          Expanded(
            child: AnimatedList(
              key: _logKey,
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
              initialItemCount: _log.length,
              itemBuilder: (context, index, animation) {
                return SizeTransition(
                  sizeFactor: CurvedAnimation(
                    parent: animation,
                    curve: Curves.easeOutCubic,
                  ),
                  child: FadeTransition(
                    opacity: animation,
                    child: _LogTile(result: _log[index]),
                  ),
                );
              },
            ),
          ),
        ] else
          const Spacer(),
      ],
    );
  }
}

/// ---------------------------------------------------------------------------
/// APK card
/// ---------------------------------------------------------------------------
class _ApkCard extends StatelessWidget {
  const _ApkCard({
    required this.selection,
    required this.installing,
    required this.onInstall,
  });

  final Selection selection;
  final bool installing;
  final VoidCallback onInstall;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final blocked = selection.targetSdk >= 1 && selection.targetSdk <= 33;
    final missingLibs = !selection.hasNativeLibs;

    return Card(
      margin: const EdgeInsets.only(top: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              selection.label,
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              selection.packageName,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (selection.versionName.isNotEmpty)
                  _MetaChip(label: 'v${selection.versionName}'),
                if (selection.sizeLabel.isNotEmpty)
                  _MetaChip(label: selection.sizeLabel),
                if (selection.parts > 1)
                  _MetaChip(label: '${selection.parts} parts'),
                if (selection.minSdk > 0)
                  _MetaChip(label: 'min SDK ${selection.minSdk}'),
                if (selection.targetSdk > 0)
                  _MetaChip(
                    label: 'target SDK ${selection.targetSdk}',
                    emphasize: blocked,
                  ),
              ],
            ),
            if (blocked) ...[
              const SizedBox(height: 12),
              _NoteRow(
                icon: Icons.shield_rounded,
                color: scheme.primary,
                text:
                    'Android 14 normally blocks this old target SDK — install '
                    'will bypass the block.',
              ),
            ],
            if (missingLibs) ...[
              const SizedBox(height: 12),
              _NoteRow(
                icon: Icons.warning_amber_rounded,
                color: scheme.error,
                text:
                    'No native libraries found in the selected parts. If this app '
                    'needs them (Flutter/Unity/etc.) it will crash on launch — also '
                    'select the split APKs or the full .xapk/.apks bundle.',
              ),
            ],
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: installing ? null : onInstall,
              icon: installing
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.auto_fix_high_rounded),
              label: Text(installing ? 'Installing…' : 'Resurrect & Install'),
            ),
          ],
        ),
      ),
    );
  }
}

class _NoteRow extends StatelessWidget {
  const _NoteRow({
    required this.icon,
    required this.color,
    required this.text,
  });

  final IconData icon;
  final Color color;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 18, color: color),
        const SizedBox(width: 8),
        Expanded(
          child: Text(text, style: Theme.of(context).textTheme.bodySmall),
        ),
      ],
    );
  }
}

class _MetaChip extends StatelessWidget {
  const _MetaChip({required this.label, this.emphasize = false});

  final String label;
  final bool emphasize;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final bg = emphasize ? scheme.errorContainer : scheme.surfaceContainerHighest;
    final fg = emphasize ? scheme.onErrorContainer : scheme.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelMedium?.copyWith(color: fg),
      ),
    );
  }
}

class _LogTile extends StatelessWidget {
  const _LogTile({required this.result});

  final InstallResult result;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ListTile(
        leading: Icon(
          result.success ? Icons.check_circle_rounded : Icons.error_rounded,
          color: result.success ? scheme.primary : scheme.error,
        ),
        title: Text(
          result.label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text(
          result.success ? result.packageName : result.message,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }
}

/// ---------------------------------------------------------------------------
/// Connection panel
/// ---------------------------------------------------------------------------
class _ConnectionPanel extends StatelessWidget {
  const _ConnectionPanel({
    required this.pairPortCtrl,
    required this.pairCodeCtrl,
    required this.connPortCtrl,
    required this.status,
    required this.connected,
    required this.hasPaired,
    required this.showPairing,
    required this.onConnect,
    required this.onDisconnect,
    required this.onFloat,
    required this.onPairAgain,
    required this.onBackToConnect,
  });

  final TextEditingController pairPortCtrl;
  final TextEditingController pairCodeCtrl;
  final TextEditingController connPortCtrl;
  final ConnStatus status;
  final bool connected;
  final bool hasPaired;
  final bool showPairing;
  final VoidCallback? onConnect;
  final VoidCallback onDisconnect;
  final VoidCallback? onFloat;
  final VoidCallback onPairAgain;
  final VoidCallback onBackToConnect;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.fromLTRB(12, 12, 12, 6),
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(child: _StatusBanner(status: status)),
                if (hasPaired) ...[
                  const SizedBox(width: 8),
                  const _PairedChip(),
                ],
              ],
            ),
            AnimatedSize(
              duration: const Duration(milliseconds: 250),
              curve: Curves.easeOut,
              alignment: Alignment.topCenter,
              child: showPairing
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const SizedBox(height: 16),
                        Row(
                          children: [
                            Expanded(
                              child: _PortField(
                                controller: pairPortCtrl,
                                label: 'Pairing port',
                                enabled: !connected,
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: TextField(
                                controller: pairCodeCtrl,
                                enabled: !connected,
                                keyboardType: TextInputType.number,
                                decoration: const InputDecoration(
                                  labelText: 'Pairing code',
                                  border: OutlineInputBorder(),
                                  isDense: true,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ],
                    )
                  : const SizedBox(width: double.infinity),
            ),
            const SizedBox(height: 12),
            _PortField(
              controller: connPortCtrl,
              label: 'Connection port (optional)',
              enabled: !connected,
            ),
            const SizedBox(height: 6),
            Text(
              'Leave the connection port blank to auto-detect it after pairing.',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: connected ? onDisconnect : onConnect,
              icon: status.busy
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Icon(
                      connected
                          ? Icons.logout_rounded
                          : showPairing
                          ? Icons.bolt_rounded
                          : Icons.link_rounded,
                    ),
              label: Text(
                connected
                    ? 'Disconnect'
                    : status.busy
                    ? status.label
                    : showPairing
                    ? 'Pair & Connect'
                    : 'Connect',
              ),
            ),
            if (!connected) ...[
              const SizedBox(height: 8),
              if (showPairing) ...[
                OutlinedButton.icon(
                  onPressed: status.busy ? null : onFloat,
                  icon: const Icon(Icons.picture_in_picture_alt_rounded),
                  label: const Text('Float over Settings to pair'),
                ),
                if (hasPaired)
                  TextButton(
                    onPressed: status.busy ? null : onBackToConnect,
                    child: const Text('Back to Connect'),
                  ),
              ] else
                OutlinedButton.icon(
                  onPressed: status.busy ? null : onPairAgain,
                  icon: const Icon(Icons.qr_code_2_rounded),
                  label: const Text('Pair again'),
                ),
            ],
          ],
        ),
      ),
    );
  }
}

class _PairedChip extends StatelessWidget {
  const _PairedChip();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: scheme.primaryContainer,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.verified_rounded, size: 16, color: scheme.onPrimaryContainer),
          const SizedBox(width: 4),
          Text(
            'Paired',
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
              color: scheme.onPrimaryContainer,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

class _PortField extends StatelessWidget {
  const _PortField({
    required this.controller,
    required this.label,
    required this.enabled,
  });

  final TextEditingController controller;
  final String label;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      enabled: enabled,
      keyboardType: TextInputType.number,
      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
      decoration: InputDecoration(
        labelText: label,
        border: const OutlineInputBorder(),
        isDense: true,
      ),
    );
  }
}

class _StatusBanner extends StatelessWidget {
  const _StatusBanner({required this.status});

  final ConnStatus status;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (Color bg, Color fg) = switch (status) {
      ConnStatus.connected => (
        scheme.primaryContainer,
        scheme.onPrimaryContainer,
      ),
      ConnStatus.error => (scheme.errorContainer, scheme.onErrorContainer),
      ConnStatus.pairing || ConnStatus.connecting => (
        scheme.tertiaryContainer,
        scheme.onTertiaryContainer,
      ),
      ConnStatus.disconnected => (
        scheme.surfaceContainerHighest,
        scheme.onSurfaceVariant,
      ),
    };

    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOut,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          _SpinningIcon(icon: status.icon, color: fg, spinning: status.busy),
          const SizedBox(width: 12),
          Text(
            status.label,
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
              color: fg,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

class _SpinningIcon extends StatefulWidget {
  const _SpinningIcon({
    required this.icon,
    required this.color,
    required this.spinning,
  });

  final IconData icon;
  final Color color;
  final bool spinning;

  @override
  State<_SpinningIcon> createState() => _SpinningIconState();
}

class _SpinningIconState extends State<_SpinningIcon>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  );

  @override
  void initState() {
    super.initState();
    if (widget.spinning) _controller.repeat();
  }

  @override
  void didUpdateWidget(covariant _SpinningIcon old) {
    super.didUpdateWidget(old);
    if (widget.spinning && !_controller.isAnimating) {
      _controller.repeat();
    } else if (!widget.spinning && _controller.isAnimating) {
      _controller.stop();
      _controller.value = 0;
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return RotationTransition(
      turns: _controller,
      child: Icon(widget.icon, color: widget.color),
    );
  }
}

/// ---------------------------------------------------------------------------
/// Empty / hint state
/// ---------------------------------------------------------------------------
class _EmptyHint extends StatelessWidget {
  const _EmptyHint({
    required this.icon,
    required this.title,
    required this.message,
  });

  final IconData icon;
  final String title;
  final String message;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, constraints) {
        return SingleChildScrollView(
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: constraints.maxHeight),
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(icon, size: 56, color: scheme.outline),
                    const SizedBox(height: 16),
                    Text(title, style: Theme.of(context).textTheme.titleLarge),
                    const SizedBox(height: 8),
                    Text(
                      message,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';
import '../../services/realtime.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';

class ChatScreen extends StatefulWidget {
  final int loadId;
  final String title;
  const ChatScreen({super.key, required this.loadId, required this.title});
  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final List _msgs = [];
  final _text = TextEditingController();
  final _scroll = ScrollController();
  void Function()? _unsub;

  @override
  void initState() {
    super.initState();
    _load();
    _unsub = Realtime.instance.subscribe('/topic/loads/${widget.loadId}/chat', (m) {
      if (_msgs.any((x) => x['id'] == m['id'])) return;
      setState(() => _msgs.add(m));
      _toBottom();
    });
  }

  @override
  void dispose() { _unsub?.call(); super.dispose(); }

  Future<void> _load() async {
    try {
      final r = await context.read<Session>().api.get('/api/loads/${widget.loadId}/chat');
      setState(() { _msgs..clear()..addAll(r); });
      _toBottom();
    } catch (e) {
      if (mounted) toast(context, e, error: true);
    }
  }

  void _toBottom() => WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
      });

  Future<void> _send() async {
    final t = _text.text.trim();
    if (t.isEmpty) return;
    _text.clear();
    if (!Realtime.instance.send('/app/loads/${widget.loadId}/chat', {'body': t})) {
      try {
        final m = await context.read<Session>().api.post('/api/loads/${widget.loadId}/chat', {'body': t});
        if (!_msgs.any((x) => x['id'] == m['id'])) setState(() => _msgs.add(m));
      } catch (e) {
        if (mounted) toast(context, e, error: true);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = context.read<Session>().id;
    return Scaffold(
      appBar: AppBar(title: Text('Chat · ${widget.title}')),
      body: Column(children: [
        Expanded(child: ListView.builder(
          controller: _scroll,
          padding: const EdgeInsets.all(12),
          itemCount: _msgs.length,
          itemBuilder: (_, i) {
            final m = _msgs[i];
            final sys = m['senderId'] == null || m['msgType'] == 'SYSTEM' || m['msgType'] == 'CALL_LOG';
            final mine = m['senderId'] == me;
            final time = DateFormat.Hm().format(DateTime.parse(m['createdAt']).toLocal());
            if (sys) {
              return Padding(padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Text('${m['body']} · $time', textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 12, fontStyle: FontStyle.italic, color: Colors.grey)));
            }
            return Align(
              alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
              child: Container(
                margin: const EdgeInsets.symmetric(vertical: 3),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * .75),
                decoration: BoxDecoration(
                  color: mine ? const Color(0xFF1F3BB3) : Colors.grey.shade200,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                  Text(m['body'], style: TextStyle(color: mine ? Colors.white : Colors.black)),
                  Text(time, style: TextStyle(fontSize: 10, color: mine ? Colors.white70 : Colors.black45)),
                ]),
              ),
            );
          },
        )),
        SafeArea(child: Padding(
          padding: const EdgeInsets.all(8),
          child: Row(children: [
            Expanded(child: TextField(controller: _text, minLines: 1, maxLines: 4, textCapitalization: TextCapitalization.sentences,
                decoration: const InputDecoration(hintText: 'Message', isDense: true))),
            IconButton.filled(onPressed: _send, icon: const Icon(Icons.send)),
          ]),
        )),
      ]),
    );
  }
}

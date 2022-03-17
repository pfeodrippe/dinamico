import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:json_dynamic_widget/json_dynamic_widget.dart';
import 'dart:async';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
        title: 'Simple Example',
        theme: ThemeData(
          primarySwatch: Colors.blue,
        ),
        home: const DynamicText());
  }
}

class DynamicText extends StatefulWidget {
  final url = 'http://localhost:3001/flutter-app';

  const DynamicText({Key? key}) : super(key: key);

  @override
  _DynamicTextState createState() => _DynamicTextState();
}

class _DynamicTextState extends State<DynamicText> {
  final Stream<http.Response> _response = (() {
    late final StreamController<http.Response> controller;
    late http.Response _lastResponse;
    bool _hasLastResponse = false;
    controller = StreamController<http.Response>(
      onListen: () async {
        while (true) {
          await Future<void>.delayed(const Duration(milliseconds: 200));
          var response =
              await http.get(Uri.parse('http://localhost:3001/flutter-app'));
          if (!_hasLastResponse || _lastResponse.body != response.body) {
            controller.add(response);
            _hasLastResponse = true;
            _lastResponse = response;
          }
        }
      },
    );
    return controller.stream;
  })();

  @override
  Widget build(BuildContext context) {
    return StreamBuilder(
      builder: (context, AsyncSnapshot<http.Response> snapshot) {
        var body = snapshot.data?.body;
        if (snapshot.hasData && body != null) {
          var widgetJson = json.decode(body);
          var widget = JsonWidgetData.fromDynamic(
            widgetJson,
          );
          if (widget != null) {
            return widget.build(context: context);
          } else {
            return const Scaffold(
              body: Center(
                child: CircularProgressIndicator(),
              ),
            );
          }
        } else {
          return const Scaffold(
            body: Center(
              child: CircularProgressIndicator(),
            ),
          );
        }
      },
      stream: _response,
    );
  }
}

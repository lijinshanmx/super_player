// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:super_player/super_player.dart';
import 'package:super_player/network_player.dart';

void main() {
  runApp(
    MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('super player'),
        ),
        body: NetworkPlayerLifeCycle(
          'https://attachments-cdn.shimo.im/Tn0JPRTC3K0g8Y6e/test.mp4?attname=test.mp4',
          (BuildContext context, VideoPlayerController controller) =>
              AspectRatioVideo(controller),
        ),
      ),
    ),
  );
}

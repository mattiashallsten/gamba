GambaString {
	var <buffer, <num, synthdef, parent;

	var last_node_id, looper;
	var buffer_time;

	*new {| buffer, num, synthdef, parent |
		^super.newCopyArgs(buffer, num, synthdef, parent).init;
	}

	init {
		last_node_id = nil;
	}

	play {| ratio, repeat = false, in_looper = false |
		ratio = ratio ? Ratio(1,1);

		if(in_looper.not && looper.notNil, {
			if(parent.debug, { "in 'GambaString.play': new note, stopping looper...".postln; });
			looper.stop;
			looper = nil;
		});
		
		if(parent.debug, { "in 'GambaString.play': playing string with ratio %...".format(ratio.asString).postln; });

		if(last_node_id.notNil, {
			parent.server.sendMsg("/n_set", last_node_id, "gate", 0);
		});

		last_node_id = parent.server.nextNodeID;
		
		parent.server.sendMsg("/s_new",
			synthdef.asString,     // synth definition name
			last_node_id,	       // synth ID
			1,				       // add action
			parent.target.nodeID,  // add target ID
			"rate", ratio.asNum,
			"buf", buffer.bufnum,
			"out", parent.out

			// add actions:

			// 0. add the new node to the the head of the group
			//    specified by the add target ID.

			// 1. add the new node to the the tail of the group
			//    specified by the add target ID.

			// 2. add the new node just before the node specified by
			//    the add target ID.

			// 3. add the new node just after the node specified by
			//    the add target ID.

			// 4. the new node replaces the node specified by the add
			//    target ID. The target node is freed.

		);
		if(parent.debug, {"in 'GambaString.play': playing gambastring with node id %".format(last_node_id).postln });

		if(repeat, {
			buffer_time = buffer.numFrames / buffer.sampleRate / ratio.asNum;

			if(parent.debug, { "in 'GambaString.play': starting looper, waiting for % seconds...".format(buffer_time).postln; });

			looper = fork {
				wait(buffer_time);
				if(parent.debug, { "in 'GambaString.play': % seconds have passed, repeating...".format(buffer_time).postln; });
				this.play(ratio, true, true);
			}
		});
		^last_node_id;
	}

	stop {| id |
		id = id ? last_node_id;

		if(looper.notNil, {
			if(parent.debug, { "in 'GambaString.stop': stopping looper...".postln; });
			looper.stop;
			looper = nil;
		});
		
		if(id.notNil, {
			if(parent.debug, { "in 'GambaString.stop': stopping string with id %...".format(id).postln; });
			parent.server.sendMsg("/n_set", id, "gate", 0);
			if(id == last_node_id, { last_node_id = nil; });
		});
		
	}
}

Gamba {
	var <out, <path, <target, <debug;
	var master_tune = 1;
	var <server, <frets;
	var <buffers, <strings;

	*new {| out = 0, path, target, debug = false |
		^super.newCopyArgs(out, path, target, debug).init;
	}

	init {
		"//////////////".postln;
		"// Gamba.sc //".postln;
		"//////////////".postln;
		server = Server.local;
		if(out.class == Bus, { out = out.index });
		path = path ? Platform.userExtensionDir ++ "/gamba";
		if(debug, { "in 'Gamba.init': initializing gamba with path %...".format(path).postln});
		frets = [
			Ratio(1,1),
			Ratio(28,27),
			Ratio(9,8),
			Ratio(7,6),
			Ratio(81,64),
			Ratio(21,16),
			Ratio(4,3),
			Ratio(3,2)
		];

		forkIfNeeded {
			target = target ? server.defaultGroup;
			buffers = (path ++ "/samples/*.wav").pathMatch.collect{|p|
				if(debug, { "in 'Gamba.init': loading buffer with path %...".format(p).postln; });
				Buffer.readOnServer(server, p);
			};

			server.sync;

			strings = buffers.collect{|buf, i|
				if(debug, {
					"in 'Gamba.init': assigning buffer with path % to string %".format(buf.path, i).postln;
				});
				GambaString(buf, i, "gamba_mono", this.value);
			};

			server.sync;

			if(debug, {
				"in 'Gamba.init': finished loading strings and buffers.
\tnumber of buffers loaded: %
\tnumber of strings loaded: %".format(buffers.size, strings.size).postln;
			});
			
			this.loadSynthDefs;
		}
	}

	play {| string = 0, fret = 0, repeat = false |
		// Play gamba on string 'string' and fret 'fret'. If 'repeat'
		// is true, repeat the sample after it has finished playing.
		
		if(debug, { "in 'Gamba.play': playing string: % on fret %...".format(string, fret).postln; });
		strings.clipAt(string).play(frets.clipAt(fret), repeat);
	}

	stop {| string = 0 |
		// Stop gamba on string 'string'.
		
		if(debug, { "in 'Gamba.stop': stopping string %...".format(string).postln; });
		strings.clipAt(string).stop;
	}

	loadSynthDefs {
		SynthDef(\gamba_mono, {
			| buf = 0
			, rate = 1.0
			, gate = 1
			, out = 0
			, amp = 0.8
			|

			var env = EnvGen.kr(Env.asr(0.0, 1, 0.4), gate, doneAction:2);
			var sig = PlayBuf.ar(1, buf, rate, doneAction:2);

			sig = sig * env * amp;
			Out.ar(out, sig);
		}).add;
		SynthDef(\gamba_analyze, {
			| in = 0
			, out = 0
			|
			var sig = In.ar(in);
			var freq, hasFreq;

			#freq, hasFreq = Tartini.kr(sig);

			SendReply.kr(Impulse.kr(5), "/gamba_freq", freq);

			Out.ar(out, sig);
		}).add;
	}

	tune {| string = 0, fret = 0 |
		// Play string 'string' and fret 'fret', and show a window
		// displaying the frequency.

		{
			var freq_text = StaticText().font_(Font.monospace);
			var freq_window = Window.new("Freq", Rect(100, 100, 300, 100))
			.layout_(HLayout(freq_text))
			.onClose_{
				analyze_synth.free;
				analyze_group.free;
				this.stop(string);
				analyze_osc_func.free;
			};

			var analyze_group = Group(target, \addAfter);
			var analyze_synth, analyze_osc_func;

			freq_window.front;
			
			server.sync;
			analyze_synth = Synth(\gamba_analyze, [\in, out, \out, out], analyze_group);

			this.play(string, fret, true);

			analyze_osc_func = OSCFunc({|msg|
				var freq = msg[3].round(0.01);
				{ freq_text.string_("Freq on string %, fret %:\n% Hz".format(string, fret, freq.asString)); }.fork(AppClock);
			}, "/gamba_freq");
		}.fork(AppClock)
	}
}
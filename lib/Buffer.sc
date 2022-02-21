+ Buffer {
	*readOnServer {
		| server
		, path
		, startFrame = 0
		, numFrames = -1
		, action
		, bufnum
		|

		var buffersOnServer = [];
		var foundBuffer = nil;

		server.cachedBuffersDo{|buf|
			buffersOnServer = buffersOnServer.add(buf)
		};
			
		buffersOnServer.do{|buf|
			if(path == buf.path, {
				^buf
			})
		};
		^Buffer.read(
			server,
			path,
			startFrame,
			numFrames,
			action,
			bufnum
		)
	}
}
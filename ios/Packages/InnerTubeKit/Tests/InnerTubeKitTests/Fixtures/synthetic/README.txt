Synthetic test pattern + sine; no YouTube content; never in the app bundle.
FF=/usr/local/bin/ffmpeg (9.0.2); DASH=(-movflags +dash+global_sidx+skip_trailer -frag_duration 2000000)
$FF -v error -y -f lavfi -i testsrc2=size=256x144:rate=25 -t 16 -c:v libx264 -profile:v main -b:v 25k -maxrate 25k -bufsize 50k -g 50 -keyint_min 50 -sc_threshold 0 -an "${DASH[@]}" v0.mp4
$FF -v error -y -f lavfi -i testsrc2=size=128x72:rate=25 -t 16 -c:v libx264 -profile:v main -b:v 15k -maxrate 15k -bufsize 30k -g 50 -keyint_min 50 -sc_threshold 0 -an "${DASH[@]}" v1.mp4
$FF -v error -y -f lavfi -i sine=frequency=440:sample_rate=44100 -t 16 -c:a aac -b:a 24k -ac 1 -vn "${DASH[@]}" a0.mp4
$FF -v error -y -f lavfi -i testsrc2=size=128x72:rate=25 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 16 -c:v libx264 -b:v 15k -g 50 -c:a aac -b:a 24k -ac 1 -shortest -movflags +faststart muxed.mp4
Model: python3 -I synthetic-model.py <this dir>  (writes synthetic.json)

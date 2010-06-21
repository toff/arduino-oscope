/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */


// from https://ccrma.stanford.edu/courses/422/projects/WaveFormat/
typedef struct {
  char ChunkID[4];
  int ChunkSize;
  char Format[4];

  char Subchunk1ID[4];
  int Subchunk1Size;

  short AudioFormat;
  short NumChannels;
  int SampleRate;
  int ByteRate;
  short BlockAlign;
  short BitsPerSample;

  char Subchunk2ID[4];
  int Subchunk2Size;
} wav_header_t;

class WAVRecorder {
public:
	WAVRecorder() {
		file = NULL;

	    strncpy(wav_header.ChunkID, "RIFF", 4);
	    wav_header.ChunkSize = 0; // size of the file from here, written when closing file
	    strncpy(wav_header.Format, "WAVE", 4);
	    strncpy(wav_header.Subchunk1ID, "fmt ", 4);
		wav_header.Subchunk1Size = 16;
		wav_header.AudioFormat = 1; // PCM
		wav_header.NumChannels = 1;
		wav_header.SampleRate = 44100;	// TODO : try to match sampling rate
	    wav_header.BitsPerSample = 8;
		wav_header.BlockAlign = (wav_header.NumChannels * wav_header.BitsPerSample) / 8;
	    wav_header.ByteRate = wav_header.BlockAlign * wav_header.SampleRate;
		strncpy(wav_header.Subchunk2ID, "data", 4);
	    wav_header.Subchunk2Size = 0; // size of the data, written when closing file
	}

	~WAVRecorder() {
		if (isRecording()) {
			stop();
		}
	}

	void start() {
		file = fopen("record.wav", "w");
        fwrite(&wav_header, sizeof(wav_header), 1, file);
	}

	void write(unsigned char *data, size_t size) {
		fwrite(data, size, 1, file);
	}

	void stop() {
		long file_len = ftell(file);
        wav_header.ChunkSize = file_len - 8;
        wav_header.Subchunk2Size = file_len - 44;
        // patch header
        fseek(file, SEEK_SET, 0);
        fwrite(&wav_header, sizeof(wav_header), 1, file);
		fclose(file);
        file = NULL;
	}

	bool isRecording() {
		return (file != NULL);
	}

	void toggle() {
		if (isRecording()) {
			printf("Stopping WAV recorder\n");
			stop();
		} else {
			printf("Starting WAV recorder\n");
			start();
		}
	}

	
private:
	FILE* file;
	wav_header_t wav_header;

};



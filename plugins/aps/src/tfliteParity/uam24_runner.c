/* Runs modelUAM.tflite with TensorFlow Lite C, one thread, no delegate.
 * Prints one line per vector: index and the raw float32 bits in hex.
 * Built against the 2.4.0 x86_64 simulator slice on the macOS runner.
 */
#include "uam_tflite.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int parse_word(const char *text, unsigned *out) {
    char *end = NULL;
    unsigned long value = strtoul(text, &end, 16);
    if (end == text || *end != '\0') return 0;
    *out = (unsigned)value;
    return 1;
}

static int run_one(TfLiteInterpreter *interpreter, const unsigned *words, unsigned *out_bits) {
    float input[18];
    int i;
    TfLiteTensor *input_tensor;
    const TfLiteTensor *output_tensor;
    float output;
    for (i = 0; i < 18; i++) {
        memcpy(&input[i], &words[i], sizeof(float));
    }
    input_tensor = TfLiteInterpreterGetInputTensor(interpreter, 0);
    if (input_tensor == NULL) return 0;
    if (TfLiteTensorCopyFromBuffer(input_tensor, input, sizeof(input)) != kTfLiteOk) return 0;
    if (TfLiteInterpreterInvoke(interpreter) != kTfLiteOk) return 0;
    output_tensor = TfLiteInterpreterGetOutputTensor(interpreter, 0);
    if (output_tensor == NULL) return 0;
    if (TfLiteTensorCopyToBuffer(output_tensor, &output, sizeof(output)) != kTfLiteOk) return 0;
    memcpy(out_bits, &output, sizeof(output));
    return 1;
}

int main(int argc, char **argv) {
    FILE *model_file;
    FILE *vector_file;
    unsigned char *model_bytes;
    long model_size;
    TfLiteModel *model;
    TfLiteInterpreterOptions *options;
    TfLiteInterpreter *interpreter;
    char line[512];
    int index = 0;
    const char *version;

    if (argc != 3) {
        fprintf(stderr, "usage: uam24_runner model.tflite vectors.txt\n");
        return 2;
    }
    version = TfLiteVersion();
    printf("version %s\n", version != NULL ? version : "");

    model_file = fopen(argv[1], "rb");
    if (model_file == NULL) {
        perror(argv[1]);
        return 2;
    }
    if (fseek(model_file, 0, SEEK_END) != 0) return 2;
    model_size = ftell(model_file);
    if (model_size <= 0) return 2;
    rewind(model_file);
    model_bytes = malloc((size_t)model_size);
    if (model_bytes == NULL) return 2;
    if (fread(model_bytes, 1, (size_t)model_size, model_file) != (size_t)model_size) return 2;
    fclose(model_file);

    model = TfLiteModelCreate(model_bytes, (size_t)model_size);
    if (model == NULL) {
        fprintf(stderr, "TfLiteModelCreate failed\n");
        return 1;
    }
    options = TfLiteInterpreterOptionsCreate();
    if (options == NULL) return 1;
    TfLiteInterpreterOptionsSetNumThreads(options, 1);
    interpreter = TfLiteInterpreterCreate(model, options);
    TfLiteInterpreterOptionsDelete(options);
    if (interpreter == NULL) {
        fprintf(stderr, "TfLiteInterpreterCreate failed\n");
        return 1;
    }
    if (TfLiteInterpreterAllocateTensors(interpreter) != kTfLiteOk) {
        fprintf(stderr, "TfLiteInterpreterAllocateTensors failed\n");
        return 1;
    }

    vector_file = fopen(argv[2], "r");
    if (vector_file == NULL) {
        perror(argv[2]);
        return 2;
    }
    while (fgets(line, sizeof(line), vector_file) != NULL) {
        unsigned words[19];
        unsigned actual = 0;
        int n = 0;
        char *cursor = line;
        if (line[0] == '\n' || line[0] == '\0') continue;
        while (n < 19) {
            char *token = cursor;
            while (*cursor != '\0' && *cursor != ' ' && *cursor != '\n') cursor++;
            if (*cursor != '\0') {
                *cursor = '\0';
                cursor++;
            }
            if (token[0] == '\0') break;
            if (!parse_word(token, &words[n])) {
                fprintf(stderr, "bad word on vector %d\n", index);
                return 2;
            }
            n++;
        }
        if (n != 19) {
            fprintf(stderr, "vector %d has %d words\n", index, n);
            return 2;
        }
        if (!run_one(interpreter, words, &actual)) {
            fprintf(stderr, "invoke failed on vector %d\n", index);
            return 1;
        }
        printf("%d %08x %08x\n", index, words[18], actual);
        index++;
    }
    fclose(vector_file);
    TfLiteInterpreterDelete(interpreter);
    TfLiteModelDelete(model);
    free(model_bytes);
    if (index != 67) {
        fprintf(stderr, "expected 67 vectors, ran %d\n", index);
        return 2;
    }
    return 0;
}

/* Subset of the TensorFlow Lite C API (2.10 headers) used for UAM inference.
 * Symbols match TensorFlowLiteC.xcframework 2.10.0. No delegate is declared,
 * so the caller cannot turn XNNPACK on through this header.
 */
#ifndef UAM_TFLITE_H_
#define UAM_TFLITE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct TfLiteModel TfLiteModel;
typedef struct TfLiteInterpreterOptions TfLiteInterpreterOptions;
typedef struct TfLiteInterpreter TfLiteInterpreter;
typedef struct TfLiteTensor TfLiteTensor;

typedef enum TfLiteStatus {
    kTfLiteOk = 0,
    kTfLiteError = 1
} TfLiteStatus;

const char* TfLiteVersion(void);

TfLiteModel* TfLiteModelCreate(const void* model_data, size_t model_size);
void TfLiteModelDelete(TfLiteModel* model);

TfLiteInterpreterOptions* TfLiteInterpreterOptionsCreate(void);
void TfLiteInterpreterOptionsDelete(TfLiteInterpreterOptions* options);
void TfLiteInterpreterOptionsSetNumThreads(TfLiteInterpreterOptions* options, int32_t num_threads);

TfLiteInterpreter* TfLiteInterpreterCreate(
    const TfLiteModel* model,
    const TfLiteInterpreterOptions* optional_options);
void TfLiteInterpreterDelete(TfLiteInterpreter* interpreter);

TfLiteStatus TfLiteInterpreterAllocateTensors(TfLiteInterpreter* interpreter);
TfLiteStatus TfLiteInterpreterInvoke(TfLiteInterpreter* interpreter);

TfLiteTensor* TfLiteInterpreterGetInputTensor(const TfLiteInterpreter* interpreter, int32_t input_index);
const TfLiteTensor* TfLiteInterpreterGetOutputTensor(const TfLiteInterpreter* interpreter, int32_t output_index);

TfLiteStatus TfLiteTensorCopyFromBuffer(TfLiteTensor* tensor, const void* input_data, size_t input_data_size);
TfLiteStatus TfLiteTensorCopyToBuffer(const TfLiteTensor* tensor, void* output_data, size_t output_data_size);

#ifdef __cplusplus
}
#endif

#endif

// Lazy OpenCL loader for the GPU prefill path.
//
// ggml-opencl calls the OpenCL API directly. Linking libOpenCL.so would add a DT_NEEDED entry to
// libonedge-engine.so, and on a device whose vendor does not expose libOpenCL.so to apps (or exposes
// none) the whole engine would then fail to load. Instead this file defines the entry points
// ggml-opencl uses and resolves each one on first use from the vendor library, opened with dlopen.
// The app declares <uses-native-library android:name="libOpenCL.so" android:required="false"/> so the
// linker namespace of the app may open it. Without the library every call fails with
// CL_PLATFORM_NOT_FOUND_KHR, ggml-opencl reports no platform and registers no device: the app keeps
// running on CPU/NPU exactly as before. BMOE_OPENCL_DISABLE=1 skips the dlopen altogether.
//
// The stubs below were generated from the prototypes in third_party/opencl-headers/CL/cl.h. When a
// llama.cpp bump makes ggml-opencl use another cl* function, the link fails with an undefined symbol:
// add a stub for it in the same pattern.
#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#include <CL/cl.h>
#include <CL/cl_ext.h>

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#ifndef CL_PLATFORM_NOT_FOUND_KHR
#define CL_PLATFORM_NOT_FOUND_KHR -1001
#endif

static void * g_cl_lib = NULL;
static pthread_once_t g_cl_once = PTHREAD_ONCE_INIT;

static void bmoe_cl_open(void) {
    const char * off = getenv("BMOE_OPENCL_DISABLE");
    if (off && off[0] && strcmp(off, "0") != 0) {
        __android_log_print(ANDROID_LOG_INFO, "bmoe-opencl", "OpenCL disabled by BMOE_OPENCL_DISABLE");
        return;
    }
    g_cl_lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (g_cl_lib) {
        __android_log_print(ANDROID_LOG_INFO, "bmoe-opencl", "libOpenCL.so opened");
    } else {
        const char * e = dlerror();
        __android_log_print(ANDROID_LOG_INFO, "bmoe-opencl", "libOpenCL.so not available: %s", e ? e : "?");
    }
}

// Non-zero when the vendor library could be opened (used by the app's GPU status line).
int bmoe_opencl_library_available(void) {
    pthread_once(&g_cl_once, bmoe_cl_open);
    return g_cl_lib != NULL;
}

static void * bmoe_cl_lookup(void ** slot, const char * name) {
    void * f = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    if (f) return f;
    pthread_once(&g_cl_once, bmoe_cl_open);
    if (!g_cl_lib) return NULL;
    f = dlsym(g_cl_lib, name);
    if (f) __atomic_store_n(slot, f, __ATOMIC_RELEASE);
    return f;
}

typedef cl_int (CL_API_CALL *pfn_clBuildProgram)(cl_program program, cl_uint num_devices, const cl_device_id * device_list, const char * options, void (CL_CALLBACK * pfn_notify)(cl_program program, void * user_data), void * user_data);
CL_API_ENTRY cl_int CL_API_CALL clBuildProgram(cl_program program, cl_uint num_devices, const cl_device_id * device_list, const char * options, void (CL_CALLBACK * pfn_notify)(cl_program program, void * user_data), void * user_data) {
    static pfn_clBuildProgram s_f;
    pfn_clBuildProgram f = (pfn_clBuildProgram) bmoe_cl_lookup((void **) &s_f, "clBuildProgram");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(program, num_devices, device_list, options, pfn_notify, user_data);
}

typedef cl_mem (CL_API_CALL *pfn_clCreateBuffer)(cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret);
CL_API_ENTRY cl_mem CL_API_CALL clCreateBuffer(cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret) {
    static pfn_clCreateBuffer s_f;
    pfn_clCreateBuffer f = (pfn_clCreateBuffer) bmoe_cl_lookup((void **) &s_f, "clCreateBuffer");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(context, flags, size, host_ptr, errcode_ret);
}

typedef cl_mem (CL_API_CALL *pfn_clCreateBufferWithProperties)(cl_context context, const cl_mem_properties * properties, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret);
CL_API_ENTRY cl_mem CL_API_CALL clCreateBufferWithProperties(cl_context context, const cl_mem_properties * properties, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret) {
    static pfn_clCreateBufferWithProperties s_f;
    pfn_clCreateBufferWithProperties f = (pfn_clCreateBufferWithProperties) bmoe_cl_lookup((void **) &s_f, "clCreateBufferWithProperties");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(context, properties, flags, size, host_ptr, errcode_ret);
}

typedef CL_API_PREFIX__VERSION_1_2_DEPRECATED cl_command_queue (CL_API_CALL *pfn_clCreateCommandQueue)(cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int * errcode_ret);
CL_API_ENTRY CL_API_PREFIX__VERSION_1_2_DEPRECATED cl_command_queue CL_API_CALL clCreateCommandQueue(cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int * errcode_ret) {
    static pfn_clCreateCommandQueue s_f;
    pfn_clCreateCommandQueue f = (pfn_clCreateCommandQueue) bmoe_cl_lookup((void **) &s_f, "clCreateCommandQueue");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(context, device, properties, errcode_ret);
}

typedef cl_context (CL_API_CALL *pfn_clCreateContext)(const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices, void (CL_CALLBACK * pfn_notify)(const char * errinfo, const void * private_info, size_t cb, void * user_data), void * user_data, cl_int * errcode_ret);
CL_API_ENTRY cl_context CL_API_CALL clCreateContext(const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices, void (CL_CALLBACK * pfn_notify)(const char * errinfo, const void * private_info, size_t cb, void * user_data), void * user_data, cl_int * errcode_ret) {
    static pfn_clCreateContext s_f;
    pfn_clCreateContext f = (pfn_clCreateContext) bmoe_cl_lookup((void **) &s_f, "clCreateContext");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(properties, num_devices, devices, pfn_notify, user_data, errcode_ret);
}

typedef cl_mem (CL_API_CALL *pfn_clCreateImage)(cl_context context, cl_mem_flags flags, const cl_image_format * image_format, const cl_image_desc * image_desc, void * host_ptr, cl_int * errcode_ret);
CL_API_ENTRY cl_mem CL_API_CALL clCreateImage(cl_context context, cl_mem_flags flags, const cl_image_format * image_format, const cl_image_desc * image_desc, void * host_ptr, cl_int * errcode_ret) {
    static pfn_clCreateImage s_f;
    pfn_clCreateImage f = (pfn_clCreateImage) bmoe_cl_lookup((void **) &s_f, "clCreateImage");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(context, flags, image_format, image_desc, host_ptr, errcode_ret);
}

typedef cl_kernel (CL_API_CALL *pfn_clCreateKernel)(cl_program program, const char * kernel_name, cl_int * errcode_ret);
CL_API_ENTRY cl_kernel CL_API_CALL clCreateKernel(cl_program program, const char * kernel_name, cl_int * errcode_ret) {
    static pfn_clCreateKernel s_f;
    pfn_clCreateKernel f = (pfn_clCreateKernel) bmoe_cl_lookup((void **) &s_f, "clCreateKernel");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(program, kernel_name, errcode_ret);
}

typedef cl_program (CL_API_CALL *pfn_clCreateProgramWithBinary)(cl_context context, cl_uint num_devices, const cl_device_id * device_list, const size_t * lengths, const unsigned char ** binaries, cl_int * binary_status, cl_int * errcode_ret);
CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithBinary(cl_context context, cl_uint num_devices, const cl_device_id * device_list, const size_t * lengths, const unsigned char ** binaries, cl_int * binary_status, cl_int * errcode_ret) {
    static pfn_clCreateProgramWithBinary s_f;
    pfn_clCreateProgramWithBinary f = (pfn_clCreateProgramWithBinary) bmoe_cl_lookup((void **) &s_f, "clCreateProgramWithBinary");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(context, num_devices, device_list, lengths, binaries, binary_status, errcode_ret);
}

typedef cl_program (CL_API_CALL *pfn_clCreateProgramWithSource)(cl_context context, cl_uint count, const char ** strings, const size_t * lengths, cl_int * errcode_ret);
CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithSource(cl_context context, cl_uint count, const char ** strings, const size_t * lengths, cl_int * errcode_ret) {
    static pfn_clCreateProgramWithSource s_f;
    pfn_clCreateProgramWithSource f = (pfn_clCreateProgramWithSource) bmoe_cl_lookup((void **) &s_f, "clCreateProgramWithSource");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(context, count, strings, lengths, errcode_ret);
}

typedef cl_mem (CL_API_CALL *pfn_clCreateSubBuffer)(cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type, const void * buffer_create_info, cl_int * errcode_ret);
CL_API_ENTRY cl_mem CL_API_CALL clCreateSubBuffer(cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type, const void * buffer_create_info, cl_int * errcode_ret) {
    static pfn_clCreateSubBuffer s_f;
    pfn_clCreateSubBuffer f = (pfn_clCreateSubBuffer) bmoe_cl_lookup((void **) &s_f, "clCreateSubBuffer");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(buffer, flags, buffer_create_type, buffer_create_info, errcode_ret);
}

typedef cl_int (CL_API_CALL *pfn_clEnqueueBarrierWithWaitList)(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueBarrierWithWaitList(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static pfn_clEnqueueBarrierWithWaitList s_f;
    pfn_clEnqueueBarrierWithWaitList f = (pfn_clEnqueueBarrierWithWaitList) bmoe_cl_lookup((void **) &s_f, "clEnqueueBarrierWithWaitList");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (CL_API_CALL *pfn_clEnqueueCopyBuffer)(cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset, size_t dst_offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueCopyBuffer(cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset, size_t dst_offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static pfn_clEnqueueCopyBuffer s_f;
    pfn_clEnqueueCopyBuffer f = (pfn_clEnqueueCopyBuffer) bmoe_cl_lookup((void **) &s_f, "clEnqueueCopyBuffer");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (CL_API_CALL *pfn_clEnqueueFillBuffer)(cl_command_queue command_queue, cl_mem buffer, const void * pattern, size_t pattern_size, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueFillBuffer(cl_command_queue command_queue, cl_mem buffer, const void * pattern, size_t pattern_size, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static pfn_clEnqueueFillBuffer s_f;
    pfn_clEnqueueFillBuffer f = (pfn_clEnqueueFillBuffer) bmoe_cl_lookup((void **) &s_f, "clEnqueueFillBuffer");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue, buffer, pattern, pattern_size, offset, size, num_events_in_wait_list, event_wait_list, event);
}

typedef void * (CL_API_CALL *pfn_clEnqueueMapBuffer)(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_map, cl_map_flags map_flags, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event, cl_int * errcode_ret);
CL_API_ENTRY void * CL_API_CALL clEnqueueMapBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_map, cl_map_flags map_flags, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event, cl_int * errcode_ret) {
    static pfn_clEnqueueMapBuffer s_f;
    pfn_clEnqueueMapBuffer f = (pfn_clEnqueueMapBuffer) bmoe_cl_lookup((void **) &s_f, "clEnqueueMapBuffer");
    if (!f) { if (errcode_ret) *errcode_ret = CL_PLATFORM_NOT_FOUND_KHR; return NULL; }
    return f(command_queue, buffer, blocking_map, map_flags, offset, size, num_events_in_wait_list, event_wait_list, event, errcode_ret);
}

typedef cl_int (CL_API_CALL *pfn_clEnqueueMarkerWithWaitList)(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueMarkerWithWaitList(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static pfn_clEnqueueMarkerWithWaitList s_f;
    pfn_clEnqueueMarkerWithWaitList f = (pfn_clEnqueueMarkerWithWaitList) bmoe_cl_lookup((void **) &s_f, "clEnqueueMarkerWithWaitList");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (CL_API_CALL *pfn_clEnqueueNDRangeKernel)(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t * global_work_offset, const size_t * global_work_size, const size_t * local_work_size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueNDRangeKernel(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t * global_work_offset, const size_t * global_work_size, const size_t * local_work_size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static pfn_clEnqueueNDRangeKernel s_f;
    pfn_clEnqueueNDRangeKernel f = (pfn_clEnqueueNDRangeKernel) bmoe_cl_lookup((void **) &s_f, "clEnqueueNDRangeKernel");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue, kernel, work_dim, global_work_offset, global_work_size, local_work_size, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (CL_API_CALL *pfn_clEnqueueReadBuffer)(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size, void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueReadBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size, void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static pfn_clEnqueueReadBuffer s_f;
    pfn_clEnqueueReadBuffer f = (pfn_clEnqueueReadBuffer) bmoe_cl_lookup((void **) &s_f, "clEnqueueReadBuffer");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue, buffer, blocking_read, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (CL_API_CALL *pfn_clEnqueueUnmapMemObject)(cl_command_queue command_queue, cl_mem memobj, void * mapped_ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueUnmapMemObject(cl_command_queue command_queue, cl_mem memobj, void * mapped_ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static pfn_clEnqueueUnmapMemObject s_f;
    pfn_clEnqueueUnmapMemObject f = (pfn_clEnqueueUnmapMemObject) bmoe_cl_lookup((void **) &s_f, "clEnqueueUnmapMemObject");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue, memobj, mapped_ptr, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (CL_API_CALL *pfn_clEnqueueWriteBuffer)(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size, const void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueWriteBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size, const void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static pfn_clEnqueueWriteBuffer s_f;
    pfn_clEnqueueWriteBuffer f = (pfn_clEnqueueWriteBuffer) bmoe_cl_lookup((void **) &s_f, "clEnqueueWriteBuffer");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue, buffer, blocking_write, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (CL_API_CALL *pfn_clFinish)(cl_command_queue command_queue);
CL_API_ENTRY cl_int CL_API_CALL clFinish(cl_command_queue command_queue) {
    static pfn_clFinish s_f;
    pfn_clFinish f = (pfn_clFinish) bmoe_cl_lookup((void **) &s_f, "clFinish");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue);
}

typedef cl_int (CL_API_CALL *pfn_clFlush)(cl_command_queue command_queue);
CL_API_ENTRY cl_int CL_API_CALL clFlush(cl_command_queue command_queue) {
    static pfn_clFlush s_f;
    pfn_clFlush f = (pfn_clFlush) bmoe_cl_lookup((void **) &s_f, "clFlush");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(command_queue);
}

typedef cl_int (CL_API_CALL *pfn_clGetDeviceIDs)(cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id * devices, cl_uint * num_devices);
CL_API_ENTRY cl_int CL_API_CALL clGetDeviceIDs(cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id * devices, cl_uint * num_devices) {
    static pfn_clGetDeviceIDs s_f;
    pfn_clGetDeviceIDs f = (pfn_clGetDeviceIDs) bmoe_cl_lookup((void **) &s_f, "clGetDeviceIDs");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(platform, device_type, num_entries, devices, num_devices);
}

typedef cl_int (CL_API_CALL *pfn_clGetDeviceInfo)(cl_device_id device, cl_device_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetDeviceInfo(cl_device_id device, cl_device_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static pfn_clGetDeviceInfo s_f;
    pfn_clGetDeviceInfo f = (pfn_clGetDeviceInfo) bmoe_cl_lookup((void **) &s_f, "clGetDeviceInfo");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(device, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (CL_API_CALL *pfn_clGetEventProfilingInfo)(cl_event event, cl_profiling_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetEventProfilingInfo(cl_event event, cl_profiling_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static pfn_clGetEventProfilingInfo s_f;
    pfn_clGetEventProfilingInfo f = (pfn_clGetEventProfilingInfo) bmoe_cl_lookup((void **) &s_f, "clGetEventProfilingInfo");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(event, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (CL_API_CALL *pfn_clGetKernelInfo)(cl_kernel kernel, cl_kernel_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetKernelInfo(cl_kernel kernel, cl_kernel_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static pfn_clGetKernelInfo s_f;
    pfn_clGetKernelInfo f = (pfn_clGetKernelInfo) bmoe_cl_lookup((void **) &s_f, "clGetKernelInfo");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(kernel, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (CL_API_CALL *pfn_clGetKernelSubGroupInfo)(cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name, size_t input_value_size, const void* input_value, size_t param_value_size, void* param_value, size_t* param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetKernelSubGroupInfo(cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name, size_t input_value_size, const void* input_value, size_t param_value_size, void* param_value, size_t* param_value_size_ret) {
    static pfn_clGetKernelSubGroupInfo s_f;
    pfn_clGetKernelSubGroupInfo f = (pfn_clGetKernelSubGroupInfo) bmoe_cl_lookup((void **) &s_f, "clGetKernelSubGroupInfo");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(kernel, device, param_name, input_value_size, input_value, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (CL_API_CALL *pfn_clGetKernelWorkGroupInfo)(cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetKernelWorkGroupInfo(cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static pfn_clGetKernelWorkGroupInfo s_f;
    pfn_clGetKernelWorkGroupInfo f = (pfn_clGetKernelWorkGroupInfo) bmoe_cl_lookup((void **) &s_f, "clGetKernelWorkGroupInfo");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(kernel, device, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (CL_API_CALL *pfn_clGetPlatformIDs)(cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms);
CL_API_ENTRY cl_int CL_API_CALL clGetPlatformIDs(cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms) {
    static pfn_clGetPlatformIDs s_f;
    pfn_clGetPlatformIDs f = (pfn_clGetPlatformIDs) bmoe_cl_lookup((void **) &s_f, "clGetPlatformIDs");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(num_entries, platforms, num_platforms);
}

typedef cl_int (CL_API_CALL *pfn_clGetPlatformInfo)(cl_platform_id platform, cl_platform_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetPlatformInfo(cl_platform_id platform, cl_platform_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static pfn_clGetPlatformInfo s_f;
    pfn_clGetPlatformInfo f = (pfn_clGetPlatformInfo) bmoe_cl_lookup((void **) &s_f, "clGetPlatformInfo");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(platform, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (CL_API_CALL *pfn_clGetProgramBuildInfo)(cl_program program, cl_device_id device, cl_program_build_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetProgramBuildInfo(cl_program program, cl_device_id device, cl_program_build_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static pfn_clGetProgramBuildInfo s_f;
    pfn_clGetProgramBuildInfo f = (pfn_clGetProgramBuildInfo) bmoe_cl_lookup((void **) &s_f, "clGetProgramBuildInfo");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(program, device, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (CL_API_CALL *pfn_clGetProgramInfo)(cl_program program, cl_program_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetProgramInfo(cl_program program, cl_program_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static pfn_clGetProgramInfo s_f;
    pfn_clGetProgramInfo f = (pfn_clGetProgramInfo) bmoe_cl_lookup((void **) &s_f, "clGetProgramInfo");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(program, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (CL_API_CALL *pfn_clReleaseEvent)(cl_event event);
CL_API_ENTRY cl_int CL_API_CALL clReleaseEvent(cl_event event) {
    static pfn_clReleaseEvent s_f;
    pfn_clReleaseEvent f = (pfn_clReleaseEvent) bmoe_cl_lookup((void **) &s_f, "clReleaseEvent");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(event);
}

typedef cl_int (CL_API_CALL *pfn_clReleaseKernel)(cl_kernel kernel);
CL_API_ENTRY cl_int CL_API_CALL clReleaseKernel(cl_kernel kernel) {
    static pfn_clReleaseKernel s_f;
    pfn_clReleaseKernel f = (pfn_clReleaseKernel) bmoe_cl_lookup((void **) &s_f, "clReleaseKernel");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(kernel);
}

typedef cl_int (CL_API_CALL *pfn_clReleaseMemObject)(cl_mem memobj);
CL_API_ENTRY cl_int CL_API_CALL clReleaseMemObject(cl_mem memobj) {
    static pfn_clReleaseMemObject s_f;
    pfn_clReleaseMemObject f = (pfn_clReleaseMemObject) bmoe_cl_lookup((void **) &s_f, "clReleaseMemObject");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(memobj);
}

typedef cl_int (CL_API_CALL *pfn_clReleaseProgram)(cl_program program);
CL_API_ENTRY cl_int CL_API_CALL clReleaseProgram(cl_program program) {
    static pfn_clReleaseProgram s_f;
    pfn_clReleaseProgram f = (pfn_clReleaseProgram) bmoe_cl_lookup((void **) &s_f, "clReleaseProgram");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(program);
}

typedef cl_int (CL_API_CALL *pfn_clSetKernelArg)(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value);
CL_API_ENTRY cl_int CL_API_CALL clSetKernelArg(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value) {
    static pfn_clSetKernelArg s_f;
    pfn_clSetKernelArg f = (pfn_clSetKernelArg) bmoe_cl_lookup((void **) &s_f, "clSetKernelArg");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(kernel, arg_index, arg_size, arg_value);
}

typedef cl_int (CL_API_CALL *pfn_clWaitForEvents)(cl_uint num_events, const cl_event * event_list);
CL_API_ENTRY cl_int CL_API_CALL clWaitForEvents(cl_uint num_events, const cl_event * event_list) {
    static pfn_clWaitForEvents s_f;
    pfn_clWaitForEvents f = (pfn_clWaitForEvents) bmoe_cl_lookup((void **) &s_f, "clWaitForEvents");
    if (!f) return CL_PLATFORM_NOT_FOUND_KHR;
    return f(num_events, event_list);
}


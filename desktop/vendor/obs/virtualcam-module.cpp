// Adapted for CamBridge from OBS Studio 33.0.3 (GPL-2.0-or-later).
// Changes: independent CLSID/name, shared memory, defaults, and clock recovery.
#include "virtualcam-filter.hpp"
#include "virtualcam-guid.h"

/* ========================================================================= */

static const REGPINTYPES AMSMediaTypesV = {&MEDIATYPE_Video, &MEDIASUBTYPE_NV12};

static const REGFILTERPINS AMSPinVideo = {nullptr, false, true, false, false, &CLSID_NULL, nullptr, 1, &AMSMediaTypesV};

HINSTANCE dll_inst = nullptr;
volatile long locks = 0;

/* ========================================================================= */

class VCamFactory : public IClassFactory {
	volatile long refs = 1;
	CLSID cls;

public:
	inline VCamFactory(CLSID cls_) : cls(cls_) { os_atomic_inc_long(&locks); }
	inline ~VCamFactory() { os_atomic_dec_long(&locks); }

	// IUnknown
	STDMETHODIMP QueryInterface(REFIID riid, void **p_ptr);
	STDMETHODIMP_(ULONG) AddRef();
	STDMETHODIMP_(ULONG) Release();

	// IClassFactory
	STDMETHODIMP CreateInstance(LPUNKNOWN parent, REFIID riid, void **p_ptr);
	STDMETHODIMP LockServer(BOOL lock);
};

STDMETHODIMP VCamFactory::QueryInterface(REFIID riid, void **p_ptr)
{
	if (!p_ptr) {
		return E_POINTER;
	}

	if ((riid == IID_IUnknown) || (riid == IID_IClassFactory)) {
		AddRef();
		*p_ptr = (void *)this;
		return S_OK;
	} else {
		*p_ptr = nullptr;
		return E_NOINTERFACE;
	}
}

STDMETHODIMP_(ULONG) VCamFactory::AddRef()
{
	return os_atomic_inc_long(&refs);
}

STDMETHODIMP_(ULONG) VCamFactory::Release()
{
	long new_refs = os_atomic_dec_long(&refs);
	if (new_refs == 0) {
		delete this;
		return 0;
	}

	return (ULONG)new_refs;
}

STDMETHODIMP VCamFactory::CreateInstance(LPUNKNOWN parent, REFIID riid, void **p_ptr)
{
	if (!p_ptr) {
		return E_POINTER;
	}

	*p_ptr = nullptr;

	/* don't bother supporting the "parent" functionality */
	if (parent) {
		return E_NOINTERFACE;
	}

	if (IsEqualCLSID(cls, CLSID_OBS_VirtualVideo)) {
		VCamFilter *filter = new VCamFilter();
		HRESULT result = filter->QueryInterface(riid, p_ptr);
		filter->Release();
		return result;
	}

	return E_NOINTERFACE;
}

STDMETHODIMP VCamFactory::LockServer(BOOL lock)
{
	if (lock) {
		os_atomic_inc_long(&locks);
	} else {
		os_atomic_dec_long(&locks);
	}

	return S_OK;
}

/* ========================================================================= */

static inline DWORD string_size(const wchar_t *str)
{
	return (DWORD)(wcslen(str) + 1) * sizeof(wchar_t);
}

static bool RegServer(const CLSID &cls, const wchar_t *desc, const wchar_t *file, const wchar_t *model = L"Both",
		      const wchar_t *type = L"InprocServer32")
{
	wchar_t cls_str[CHARS_IN_GUID];
	wchar_t temp[MAX_PATH];
	HKEY key = nullptr;
	HKEY subkey = nullptr;
	bool success = false;

	StringFromGUID2(cls, cls_str, CHARS_IN_GUID);

	StringCbPrintf(temp, sizeof(temp), L"Software\\Classes\\CLSID\\%s", cls_str);

	if (RegCreateKey(HKEY_CURRENT_USER, temp, &key) != ERROR_SUCCESS) {
		goto fail;
	}

	RegSetValueW(key, nullptr, REG_SZ, desc, string_size(desc));

	if (RegCreateKey(key, type, &subkey) != ERROR_SUCCESS) {
		goto fail;
	}

	RegSetValueW(subkey, nullptr, REG_SZ, file, string_size(file));
	RegSetValueExW(subkey, L"ThreadingModel", 0, REG_SZ, (const BYTE *)model, string_size(model));

	success = true;

fail:
	if (key) {
		RegCloseKey(key);
	}
	if (subkey) {
		RegCloseKey(subkey);
	}

	return success;
}

static bool UnregServer(const CLSID &cls)
{
	wchar_t cls_str[CHARS_IN_GUID];
	wchar_t temp[MAX_PATH];

	StringFromGUID2(cls, cls_str, CHARS_IN_GUID);
	StringCbPrintf(temp, sizeof(temp), L"Software\\Classes\\CLSID\\%s", cls_str);

	return RegDeleteTreeW(HKEY_CURRENT_USER, temp) == ERROR_SUCCESS;
}

static bool RegServers(bool reg)
{
	wchar_t file[MAX_PATH];

	if (!GetModuleFileNameW(dll_inst, file, MAX_PATH)) {
		return false;
	}

	if (reg) {
		return RegServer(CLSID_OBS_VirtualVideo, L"CamBridge Camera", file);
	} else {
		return UnregServer(CLSID_OBS_VirtualVideo);
	}
}

static bool RegFilters(bool reg)
{
    wchar_t id[CHARS_IN_GUID]; StringFromGUID2(CLSID_OBS_VirtualVideo,id,CHARS_IN_GUID);
    std::wstring path=L"Software\\Classes\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance\\";
    path+=id;
    if(!reg){LONG result=RegDeleteTreeW(HKEY_CURRENT_USER,path.c_str());return result==ERROR_SUCCESS||result==ERROR_FILE_NOT_FOUND;}
    HKEY key=nullptr;
    if(RegCreateKeyExW(HKEY_CURRENT_USER,path.c_str(),0,nullptr,0,KEY_WRITE,nullptr,&key,nullptr)!=ERROR_SUCCESS){return false;}
    const wchar_t *name=L"CamBridge Camera";
    LONG first=RegSetValueExW(key,L"CLSID",0,REG_SZ,reinterpret_cast<const BYTE *>(id),string_size(id));
    LONG second=RegSetValueExW(key,L"FriendlyName",0,REG_SZ,reinterpret_cast<const BYTE *>(name),string_size(name));
    RegCloseKey(key);return first==ERROR_SUCCESS&&second==ERROR_SUCCESS;
}

/* ========================================================================= */

STDAPI DllRegisterServer()
{
	if (!RegServers(true)) {
		RegServers(false);
		return E_FAIL;
	}

	

	if (!RegFilters(true)) {
		RegFilters(false);
		RegServers(false);
		
		return E_FAIL;
	}

	
	return S_OK;
}

STDAPI DllUnregisterServer()
{
	
	RegFilters(false);
	RegServers(false);
	
	return S_OK;
}

STDAPI DllInstall(BOOL install, LPCWSTR)
{
	if (!install) {
		return DllUnregisterServer();
	} else {
		return DllRegisterServer();
	}
}

STDAPI DllCanUnloadNow()
{
	return os_atomic_load_long(&locks) ? S_FALSE : S_OK;
}

STDAPI DllGetClassObject(REFCLSID cls, REFIID riid, void **p_ptr)
{
	if (!p_ptr) {
		return E_POINTER;
	}

	*p_ptr = nullptr;

	if (riid != IID_IClassFactory && riid != IID_IUnknown) {
		return E_NOINTERFACE;
	}
	if (!IsEqualCLSID(cls, CLSID_OBS_VirtualVideo)) {
		return E_INVALIDARG;
	}

	*p_ptr = (void *)new VCamFactory(cls);
	return S_OK;
}

//#define ENABLE_LOGGING

#ifdef ENABLE_LOGGING
void logcallback(DShow::LogType, const wchar_t *msg, void *)
{
	OutputDebugStringW(msg);
	OutputDebugStringW(L"\n");
}
#endif

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID)
{
	if (reason == DLL_PROCESS_ATTACH) {
#ifdef ENABLE_LOGGING
		DShow::SetLogCallback(logcallback, nullptr);
#endif
		dll_inst = inst;
	}

	return true;
}

#include <windows.h>
#include <roapi.h>
#include <shlwapi.h>
#include <SystemMediaTransportControlsInterop.h>
#include <windows.media.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.Streams.h>
#include <winrt/base.h>

#include <cwchar>
#include <mutex>
#include <string>
#include <vector>

using KinoMediaKeyCallback = void(__cdecl *)(void *, int);

static HRESULT report_hresult(const wchar_t *operation, HRESULT result) {
    if (FAILED(result)) {
        wchar_t message[256];
        swprintf_s(
            message,
            L"KinoMediaSession %ls failed HRESULT=0x%08lX\n",
            operation,
            static_cast<unsigned long>(result));
        OutputDebugStringW(message);
    }
    return result;
}

using KinoThumbnail = winrt::Windows::Storage::Streams::RandomAccessStreamReference;

static bool starts_with(const std::wstring &value, const wchar_t *prefix) {
    const size_t length = wcslen(prefix);
    return value.size() >= length && _wcsnicmp(value.c_str(), prefix, length) == 0;
}

static bool file_exists(const std::wstring &path) {
    const DWORD attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

static std::wstring file_path_to_uri(const std::wstring &path) {
    DWORD length = 32768;
    std::vector<wchar_t> buffer(length);
    if (FAILED(UrlCreateFromPathW(path.c_str(), buffer.data(), &length, 0))) {
        return L"";
    }
    return std::wstring(buffer.data(), length);
}

static std::wstring executable_directory() {
    HMODULE module = nullptr;
    GetModuleHandleExW(
        GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
        reinterpret_cast<LPCWSTR>(&executable_directory),
        &module);
    std::vector<wchar_t> buffer(32768);
    const DWORD length = GetModuleFileNameW(module, buffer.data(), static_cast<DWORD>(buffer.size()));
    if (length == 0 || length >= buffer.size()) {
        return L"";
    }
    const std::wstring executable(buffer.data(), length);
    const size_t separator = executable.find_last_of(L"\\/");
    return separator == std::wstring::npos ? L"" : executable.substr(0, separator);
}

static KinoThumbnail create_thumbnail(const std::wstring &source) {
    if (source.empty()) {
        return nullptr;
    }
    try {
        if (starts_with(source, L"http://") || starts_with(source, L"https://") || starts_with(source, L"file:")) {
            return KinoThumbnail::CreateFromUri(
                winrt::Windows::Foundation::Uri(winrt::hstring(source)));
        }
        if (file_exists(source)) {
            const std::wstring uri = file_path_to_uri(source);
            if (!uri.empty()) {
                return KinoThumbnail::CreateFromUri(
                    winrt::Windows::Foundation::Uri(winrt::hstring(uri)));
            }
        }
    } catch (const winrt::hresult_error &error) {
        report_hresult(L"CreateThumbnail", error.code());
    } catch (...) {
        report_hresult(L"CreateThumbnail", E_FAIL);
    }
    return nullptr;
}

static KinoThumbnail create_fallback_thumbnail() {
    const std::wstring directory = executable_directory();
    if (directory.empty()) {
        return nullptr;
    }
    const std::vector<std::wstring> candidates = {
        directory + L"\\resources\\app_logo.png",
        directory + L"\\resources\\kino.ico",
        directory + L"\\kino.ico",
    };
    for (const std::wstring &candidate : candidates) {
        KinoThumbnail thumbnail = create_thumbnail(candidate);
        if (thumbnail) {
            return thumbnail;
        }
    }
    return nullptr;
}

class ApartmentGuard {
public:
    ApartmentGuard() {
        const HRESULT result = RoInitialize(RO_INIT_MULTITHREADED);
        if (result == S_OK || result == S_FALSE) {
            initialized = true;
        } else if (result != RPC_E_CHANGED_MODE) {
            winrt::throw_hresult(result);
        }
    }

    ~ApartmentGuard() {
        if (initialized) {
            RoUninitialize();
        }
    }

private:
    bool initialized = false;
};

class KinoMediaSession {
public:
    KinoMediaSession(HWND window, KinoMediaKeyCallback callback, void *context)
        : callback(callback), context(context) {
        auto factory = winrt::get_activation_factory<
            winrt::Windows::Media::SystemMediaTransportControls,
            ISystemMediaTransportControlsInterop>();
        const HRESULT result = factory->GetForWindow(
            window,
            winrt::guid_of<ABI::Windows::Media::ISystemMediaTransportControls>(),
            winrt::put_abi(controls));
        if (FAILED(report_hresult(L"GetForWindow", result))) {
            winrt::throw_hresult(result);
        }
        buttonToken = controls.ButtonPressed([this](
            winrt::Windows::Media::SystemMediaTransportControls const &,
            winrt::Windows::Media::SystemMediaTransportControlsButtonPressedEventArgs const &args) {
            buttonPressed(args.Button());
        });
        controls.IsPlayEnabled(true);
        controls.IsPauseEnabled(true);
        controls.IsStopEnabled(true);
        controls.IsPreviousEnabled(true);
        controls.IsNextEnabled(true);
        controls.IsEnabled(true);
        controls.PlaybackStatus(winrt::Windows::Media::MediaPlaybackStatus::Changing);
    }

    HRESULT update(winrt::Windows::Media::MediaPlaybackStatus status) {
        try {
            ApartmentGuard apartment;
            std::scoped_lock lock(mutex);
            if (disposed) {
                return RO_E_CLOSED;
            }
            controls.PlaybackStatus(status);
            return S_OK;
        } catch (const winrt::hresult_error &error) {
            return report_hresult(L"PlaybackStatus", error.code());
        } catch (...) {
            return report_hresult(L"PlaybackStatus", E_FAIL);
        }
    }

    HRESULT updateMetadata(const wchar_t *title, const wchar_t *subtitle, const wchar_t *artworkUrl) {
        try {
            ApartmentGuard apartment;
            std::scoped_lock lock(mutex);
            if (disposed) {
                return RO_E_CLOSED;
            }
            auto updater = controls.DisplayUpdater();
            updater.Type(winrt::Windows::Media::MediaPlaybackType::Video);
            auto properties = updater.VideoProperties();
            properties.Title(winrt::hstring(title != nullptr ? title : L""));
            properties.Subtitle(winrt::hstring(subtitle != nullptr ? subtitle : L""));
            KinoThumbnail thumbnail = create_thumbnail(artworkUrl != nullptr ? artworkUrl : L"");
            if (!thumbnail) {
                thumbnail = create_fallback_thumbnail();
            }
            updater.Thumbnail(thumbnail);
            updater.Update();
            return S_OK;
        } catch (const winrt::hresult_error &error) {
            return report_hresult(L"DisplayUpdater.Update", error.code());
        } catch (...) {
            return report_hresult(L"DisplayUpdater.Update", E_FAIL);
        }
    }

    HRESULT clearMetadata() {
        try {
            ApartmentGuard apartment;
            std::scoped_lock lock(mutex);
            if (disposed) {
                return RO_E_CLOSED;
            }
            auto updater = controls.DisplayUpdater();
            updater.ClearAll();
            updater.Update();
            return S_OK;
        } catch (const winrt::hresult_error &error) {
            return report_hresult(L"DisplayUpdater.ClearAll", error.code());
        } catch (...) {
            return report_hresult(L"DisplayUpdater.ClearAll", E_FAIL);
        }
    }

    HRESULT dispose() {
        try {
            ApartmentGuard apartment;
            winrt::Windows::Media::SystemMediaTransportControls controlsCopy{nullptr};
            {
                std::scoped_lock lock(mutex);
                if (disposed) {
                    return S_OK;
                }
                disposed = true;
                controlsCopy = controls;
                callback = nullptr;
                context = nullptr;
            }
            HRESULT result = S_OK;
            if (controlsCopy) {
                try {
                    auto updater = controlsCopy.DisplayUpdater();
                    updater.ClearAll();
                    updater.Update();
                } catch (const winrt::hresult_error &error) {
                    result = report_hresult(L"Dispose.DisplayUpdater.Update", error.code());
                } catch (...) {
                    result = report_hresult(L"Dispose.DisplayUpdater.Update", E_FAIL);
                }
                controlsCopy.ButtonPressed(buttonToken);
                try {
                    controlsCopy.PlaybackStatus(winrt::Windows::Media::MediaPlaybackStatus::Stopped);
                } catch (const winrt::hresult_error &error) {
                    result = report_hresult(L"Dispose.PlaybackStatus", error.code());
                } catch (...) {
                    result = report_hresult(L"Dispose.PlaybackStatus", E_FAIL);
                }
                try {
                    controlsCopy.IsEnabled(false);
                } catch (const winrt::hresult_error &error) {
                    result = report_hresult(L"Dispose.IsEnabled", error.code());
                } catch (...) {
                    result = report_hresult(L"Dispose.IsEnabled", E_FAIL);
                }
            }
            {
                std::scoped_lock lock(mutex);
                controls = nullptr;
            }
            return result;
        } catch (const winrt::hresult_error &error) {
            return report_hresult(L"Dispose", error.code());
        } catch (...) {
            return report_hresult(L"Dispose", E_FAIL);
        }
    }

private:
    void buttonPressed(winrt::Windows::Media::SystemMediaTransportControlsButton button) {
        std::scoped_lock lock(mutex);
        if (!disposed && callback != nullptr) {
            callback(context, static_cast<int>(button));
        }
    }

    std::mutex mutex;
    winrt::Windows::Media::SystemMediaTransportControls controls{nullptr};
    winrt::event_token buttonToken{};
    KinoMediaKeyCallback callback = nullptr;
    void *context = nullptr;
    bool disposed = false;
};

extern "C" __declspec(dllexport) HRESULT __cdecl kino_windows_media_session_create(
    void *window,
    KinoMediaKeyCallback callback,
    void *context,
    void **session) {
    if (session == nullptr) {
        return E_POINTER;
    }
    *session = nullptr;
    try {
        ApartmentGuard apartment;
        *session = new KinoMediaSession(reinterpret_cast<HWND>(window), callback, context);
        return S_OK;
    } catch (const winrt::hresult_error &error) {
        return report_hresult(L"Create", error.code());
    } catch (...) {
        return report_hresult(L"Create", E_FAIL);
    }
}

extern "C" __declspec(dllexport) HRESULT __cdecl kino_windows_media_session_update(
    void *session,
    int playbackStatus) {
    if (session == nullptr) {
        return E_POINTER;
    }
    return static_cast<KinoMediaSession *>(session)->update(
        static_cast<winrt::Windows::Media::MediaPlaybackStatus>(playbackStatus));
}

extern "C" __declspec(dllexport) HRESULT __cdecl kino_windows_media_session_update_metadata(
    void *session,
    const wchar_t *title,
    const wchar_t *subtitle,
    const wchar_t *artworkUrl) {
    if (session == nullptr) {
        return E_POINTER;
    }
    return static_cast<KinoMediaSession *>(session)->updateMetadata(title, subtitle, artworkUrl);
}

extern "C" __declspec(dllexport) HRESULT __cdecl kino_windows_media_session_clear_metadata(void *session) {
    if (session == nullptr) {
        return E_POINTER;
    }
    return static_cast<KinoMediaSession *>(session)->clearMetadata();
}

extern "C" __declspec(dllexport) HRESULT __cdecl kino_windows_media_session_dispose(void *session) {
    if (session == nullptr) {
        return E_POINTER;
    }
    auto *mediaSession = static_cast<KinoMediaSession *>(session);
    const HRESULT result = mediaSession->dispose();
    delete mediaSession;
    return result;
}

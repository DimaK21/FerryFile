(function () {
  'use strict';

  var STORAGE_KEY = 'ferryfile-language';
  var translations = {
    en: {
      brand: 'FerryFile',
      language: {
        switch_to: 'Русский',
        switch_aria_label: 'Switch to Russian'
      },
      login: {
        title: 'FerryFile — Login',
        subtitle: 'Enter the PIN shown in the FerryFile app',
        pin: 'PIN',
        unlock: 'Unlock',
        checking: 'Checking…',
        pin_length: 'The PIN is 6 digits',
        too_many_attempts: 'Too many attempts, wait 30s',
        wrong_pin: 'Wrong PIN',
        unexpected_error: 'Unexpected error, please try again',
        network_error: 'Network error, please try again'
      },
      files: {
        title: 'FerryFile',
        home: 'Home',
        logout: 'Logout',
        transferring: 'Transferring…',
        file_path: 'File path',
        upload_files: 'Upload files',
        root_hint: 'Open one of the folders below to upload files into it.',
        selection_count: 'Selected: {count}',
        download: 'Download',
        clear: 'Clear',
        loading: 'Loading…',
        drop_zone: 'Drag & drop files here or click to choose files',
        no_shared_folders: 'No folders shared yet — add one in the FerryFile app on your phone',
        empty_folder: 'This folder is empty',
        select_file: 'Select {name}',
        download_folder: 'Download folder {name}',
        load_failed: 'Failed to load folder contents',
        server_error: 'Server error ({status})',
        load_error: 'Load error',
        load_error_details: 'Load error: {message}',
        upload_error: 'Upload error',
        upload_error_details: 'Upload error: {message}',
        transfer_in_progress: 'A transfer is already in progress',
        open_folder_first: 'Open a folder first — the home screen only lists shared folders',
        transfer_error: 'Transfer error'
      },
      units: {
        bytes: 'B',
        kilobytes: 'KB',
        megabytes: 'MB',
        gigabytes: 'GB'
      },
      progress: {
        eta: 'ETA {seconds}s'
      },
      transfer: {
        complete: {
          one: '{count} file transferred{size}',
          other: '{count} files transferred{size}'
        }
      }
    },
    ru: {
      brand: 'FerryFile',
      language: {
        switch_to: 'English',
        switch_aria_label: 'Переключить на английский'
      },
      login: {
        title: 'FerryFile — Вход',
        subtitle: 'Введите PIN, показанный в приложении FerryFile',
        pin: 'PIN',
        unlock: 'Разблокировать',
        checking: 'Проверка…',
        pin_length: 'PIN состоит из 6 цифр',
        too_many_attempts: 'Слишком много попыток, подождите 30 секунд',
        wrong_pin: 'Неверный PIN',
        unexpected_error: 'Произошла ошибка, попробуйте ещё раз',
        network_error: 'Ошибка сети, попробуйте ещё раз'
      },
      files: {
        title: 'FerryFile',
        home: 'Главная',
        logout: 'Выйти',
        transferring: 'Передача…',
        file_path: 'Путь к файлу',
        upload_files: 'Загрузить файлы',
        root_hint: 'Откройте одну из папок ниже, чтобы загрузить файлы в неё.',
        selection_count: 'Выбрано: {count}',
        download: 'Скачать',
        clear: 'Очистить',
        loading: 'Загрузка…',
        drop_zone: 'Перетащите файлы сюда или нажмите, чтобы выбрать файлы',
        no_shared_folders: 'Общие папки не добавлены — добавьте папку в приложении FerryFile на телефоне',
        empty_folder: 'Папка пуста',
        select_file: 'Выбрать {name}',
        download_folder: 'Скачать папку {name}',
        load_failed: 'Не удалось загрузить содержимое папки',
        server_error: 'Ошибка сервера ({status})',
        load_error: 'Ошибка загрузки',
        load_error_details: 'Ошибка загрузки: {message}',
        upload_error: 'Ошибка отправки',
        upload_error_details: 'Ошибка отправки: {message}',
        transfer_in_progress: 'Передача уже выполняется',
        open_folder_first: 'Сначала откройте папку — на главном экране отображаются только общие папки',
        transfer_error: 'Ошибка передачи'
      },
      units: {
        bytes: 'Б',
        kilobytes: 'КБ',
        megabytes: 'МБ',
        gigabytes: 'ГБ'
      },
      progress: {
        eta: 'Осталось: {seconds} с'
      },
      transfer: {
        complete: {
          one: '{count} файл передан{size}',
          few: '{count} файла передано{size}',
          many: '{count} файлов передано{size}',
          other: '{count} файла передано{size}'
        }
      }
    }
  };

  function readStoredLanguage() {
    try {
      var stored = window.localStorage.getItem(STORAGE_KEY);
      return stored === 'ru' || stored === 'en' ? stored : null;
    } catch (ignored) {
      return null;
    }
  }

  function detectLanguage() {
    var browserLanguage = (navigator.language || (navigator.languages && navigator.languages[0]) || '')
      .toLowerCase();
    return browserLanguage.indexOf('ru') === 0 ? 'ru' : 'en';
  }

  var language = readStoredLanguage() || detectLanguage();

  function pluralCategory(count) {
    if (language !== 'ru') return count === 1 ? 'one' : 'other';

    var absolute = Math.abs(count) % 100;
    var lastDigit = absolute % 10;
    if (absolute >= 11 && absolute <= 19) return 'many';
    if (lastDigit === 1) return 'one';
    if (lastDigit >= 2 && lastDigit <= 4) return 'few';
    return 'many';
  }

  function lookup(key) {
    return key.split('.').reduce(function (value, part) {
      return value == null ? null : value[part];
    }, translations[language]);
  }

  function translate(key, params) {
    params = params || {};
    var value = lookup(key);
    if (value && typeof value === 'object') {
      value = value[pluralCategory(Number(params.count))] || value.other || value.one;
    }
    if (typeof value !== 'string') return key;

    return value.replace(/\{(\w+)\}/g, function (match, name) {
      return params[name] == null ? '' : params[name];
    });
  }

  function applyTranslations() {
    document.documentElement.lang = language;

    var textElements = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < textElements.length; i++) {
      textElements[i].textContent = translate(textElements[i].getAttribute('data-i18n'));
    }

    var ariaElements = document.querySelectorAll('[data-i18n-aria-label]');
    for (var j = 0; j < ariaElements.length; j++) {
      ariaElements[j].setAttribute(
        'aria-label',
        translate(ariaElements[j].getAttribute('data-i18n-aria-label'))
      );
    }

    var languageToggle = document.getElementById('language-toggle');
    if (languageToggle) {
      languageToggle.textContent = translate('language.switch_to');
      languageToggle.setAttribute('aria-label', translate('language.switch_aria_label'));
      if (!languageToggle.getAttribute('data-language-bound')) {
        languageToggle.setAttribute('data-language-bound', 'true');
        languageToggle.addEventListener('click', function () {
          setLanguage(language === 'ru' ? 'en' : 'ru');
        });
      }
    }
  }

  function setLanguage(nextLanguage) {
    language = nextLanguage === 'ru' ? 'ru' : 'en';
    try {
      window.localStorage.setItem(STORAGE_KEY, language);
    } catch (ignored) {
      // Continue without persistence when storage is unavailable.
    }
    applyTranslations();
    document.dispatchEvent(new CustomEvent('ferryfile-language-change'));
  }

  window.FerryFileI18n = {
    getLanguage: function () { return language; },
    setLanguage: setLanguage,
    t: translate
  };

  applyTranslations();
}());

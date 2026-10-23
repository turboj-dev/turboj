export const features = [
  {
    number: '01',
    title: 'Write Java',
    paragraphs: [
      'A clean source editor with syntax highlighting, line numbers, and a familiar keyboard-driven interface.',
      'The code gets the space. Everything else stays out of the way.',
    ],
  },
  {
    number: '02',
    title: 'Compile without the ceremony',
    paragraphs: [
      'Check and compile Java from the editor.',
      'Compiler feedback appears directly in the message pane. No need to switch to another application just to see what went wrong.',
    ],
  },
  {
    number: '03',
    title: 'Run where you write',
    paragraphs: [
      'Run a Java program and see its output immediately below the source.',
      'Edit. Compile. Run. Repeat.',
    ],
  },
  {
    number: '04',
    title: 'Keep your hands on the keyboard',
    paragraphs: [
      'Save, open, compile, and run using shortcuts inspired by classic development tools.',
      'F2 to save. F9 to make. Ctrl+F9 to run.',
      'A workflow designed around your keyboard, not endless clicking.',
    ],
  },
  {
    number: '05',
    title: "See what's happening",
    paragraphs: [
      'Compiler status, errors, cache hits, and execution timings are visible without opening a separate dashboard.',
      'Useful feedback, right when you need it.',
    ],
  },
  {
    number: '06',
    title: 'Classic by design',
    paragraphs: [
      'Blue editor. Cyan console. Simple menus. Clear borders.',
      'TurboJ brings back the development environment, not the limitations of the computers that ran it.',
    ],
  },
] as const;

export const workflow = [
  {
    number: '01',
    title: 'EDIT',
    paragraphs: [
      'Write your Java code directly in TurboJ.',
      'No project wizard required for a simple example.',
    ],
  },
  {
    number: '02',
    title: 'COMPILE',
    paragraphs: ['Use the compiler from the editor and get feedback in the message pane.'],
  },
  {
    number: '03',
    title: 'RUN',
    paragraphs: [
      'Execute your program and read the output without leaving TurboJ.',
      'Then change something and do it again.',
    ],
  },
] as const;

export const faq = [
  {
    question: 'What is TurboJ?',
    answer: 'TurboJ is a lightweight Java development environment inspired by classic DOS editors, especially the Turbo C era. Its focus is a simple, fast workflow for editing, compiling, and executing Java programs.',
  },
  {
    question: 'Is TurboJ a replacement for IntelliJ IDEA or Eclipse?',
    answer: 'No. Those tools solve much larger problems. TurboJ focuses on small programs, learning, experimentation, and quick development tasks where a full IDE may be unnecessary.',
  },
  {
    question: 'Can I write real Java programs?',
    answer: "Yes. TurboJ works with Java source code and executes real Java programs. It's not a Java simulator or a browser-based playground.",
  },
  {
    question: 'Why does it look like an editor from the 1990s?',
    answer: "Because we liked that development experience. The keyboard shortcuts, compact layout, and immediate feedback were good ideas then. They're still good ideas today.",
  },
  {
    question: 'What platforms does TurboJ support?',
    answer: 'TurboJ is currently being developed for macOS. Other platforms have not been announced.',
  },
  {
    question: 'Is TurboJ available to download?',
    answer: 'A public download will be linked here once a distributable build is ready.',
  },
] as const;

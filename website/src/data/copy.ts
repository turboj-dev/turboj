export const features = [
  {
    number: '01',
    title: 'Write Java',
    paragraphs: [
      'A clean source editor with syntax highlighting, line numbers, and a familiar keyboard-driven interface.',
    ],
  },
  {
    number: '02',
    title: 'Compile without the ceremony',
    paragraphs: [
      'Compile Java from the editor. Diagnostics appear directly in the message pane.',
    ],
  },
  {
    number: '03',
    title: 'Run where you write',
    paragraphs: [
      'Run a Java program and see its output immediately below the source.',
    ],
  },
  {
    number: '04',
    title: 'Keep your hands on the keyboard',
    paragraphs: [
      'F2 to save. F9 to make. Ctrl+F9 to run.',
    ],
  },
  {
    number: '05',
    title: "See what's happening",
    paragraphs: [
      'Compiler status, errors, cache hits, and execution timings are visible without opening a separate dashboard.',
    ],
  },
  {
    number: '06',
    title: 'Choose your theme',
    paragraphs: [
      '100 curated editor themes, plus Turbo Classic, Modern Dark, and Modern Light. Search, preview, or spin the random theme wheel.',
    ],
  },
] as const;

export const javaIntelligenceFeatures = [
  {
    title: 'Smart Java Completion',
    description: 'Type-aware Java suggestions, directly inside the editor.',
  },
  {
    title: 'Code Navigation',
    description: 'Jump to method and class declarations with a click.',
  },
  {
    title: 'Quick Documentation',
    description: 'Read method signatures and Javadoc without leaving the editor.',
  },
] as const;

export const aiCompletionFeature = {
  title: 'AI completion, when you want it.',
  label: 'Optional · Disabled by default',
  description: 'Preview the next statement or a block of Java directly in your editor. Use an optional local FIM model, accept with Tab, or dismiss with Escape. Java intelligence stays available with AI off.',
  status: 'Local Ollama inference verified on macOS. The OpenAI Responses adapter is implemented and protocol-tested; live cloud validation is pending.',
  linkLabel: 'AI setup, privacy, and validation',
} as const;

export const workflow = [
  {
    number: '01',
    title: 'EDIT',
    paragraphs: [
      'Write your Java code directly in TurboJ.',
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

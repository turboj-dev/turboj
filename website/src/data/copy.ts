export const features = [
  {
    number: '01',
    title: 'Write & Navigate',
    paragraphs: [
      'Edit Java with semantic completion, method signatures, Javadoc, and navigation across your source code.',
    ],
  },
  {
    number: '02',
    title: 'Compile & Run',
    paragraphs: [
      'Compile Java in memory, run programs through the integrated console, and evaluate code with JShell.',
    ],
  },
  {
    number: '03',
    title: 'Maven Projects',
    paragraphs: [
      'Open Maven projects, resolve dependencies, and work across modules without rebuilding the project after every edit.',
    ],
  },
  {
    number: '04',
    title: 'Refactor & Format',
    paragraphs: [
      'Use semantic Java refactoring, change previews, and consistent source formatting directly in the editor.',
    ],
  },
  {
    number: '05',
    title: 'JUnit & Automation',
    paragraphs: [
      'Run tests by method, class, or project. Optionally run programs or tests automatically after a successful save and compile.',
    ],
  },
  {
    number: '06',
    title: 'Built for the keyboard.',
    paragraphs: [
      'Edit with line actions and multiple carets. Choose from four keyboard profiles, customize shortcuts, and find commands in the palette. Select from 103 editor themes.',
    ],
  },
] as const;

export const javaIntelligenceFeatures = [
  {
    title: 'Smart Java Completion',
    description: 'Semantic and inline suggestions, overloads, and parameter hints, without AI.',
  },
  {
    title: 'Code Navigation',
    description: 'Go to Declaration and retrace your steps with navigation history.',
  },
  {
    title: 'Quick Documentation',
    description: 'Read method signatures and Javadoc without leaving the editor.',
  },
] as const;

export const aiCompletionFeature = {
  title: 'AI completion, when you want it.',
  label: 'Optional · Disabled by default',
  description: 'Optional local or cloud-assisted inline suggestions. Java completion, navigation, and refactoring continue to work without AI.',
  status: 'Local Ollama inference verified with a real model. The llama.cpp FIM and OpenAI Responses adapters are protocol-tested; live cloud inference has not been verified. Cloud access requires explicit consent and credentials use macOS Keychain. Context is limited to the current buffer; models are not included.',
  linkLabel: 'AI setup, privacy, and validation',
} as const;

export const workflow = [
  {
    number: '01',
    title: 'WRITE',
    paragraphs: [
      'Write Java with semantic completion, source navigation, and quick documentation.',
    ],
  },
  {
    number: '02',
    title: 'COMPILE',
    paragraphs: ['Compile the current code in memory and see diagnostics inside TurboJ.'],
  },
  {
    number: '03',
    title: 'RUN',
    paragraphs: [
      'Run your program in the integrated console, with arguments and interactive input when needed.',
    ],
  },
  {
    number: '04',
    title: 'TEST',
    paragraphs: [
      'Run JUnit tests, inspect failures, or enable automatic testing after a successful save and compile.',
    ],
  },
] as const;

export const faq = [
  {
    question: 'What is TurboJ?',
    answer: 'TurboJ is a native Java development environment for macOS. It combines a minimal keyboard-first interface with Java completion, compilation, execution, Maven projects, refactoring, formatting, and testing.',
  },
  {
    question: 'Does TurboJ support Maven projects?',
    answer: "Yes. TurboJ can open Maven projects, resolve dependencies, and work across multiple modules. Its fast compiler handles everyday development, while explicit Maven commands run the project's full build lifecycle.",
  },
  {
    question: 'Can TurboJ run JUnit tests automatically?',
    answer: 'Yes. Auto Test on Save and Auto Run on Save are separate options, disabled by default. After saving, TurboJ compiles the current code and runs enabled actions only when compilation succeeds.',
  },
  {
    question: 'Does Java completion require AI?',
    answer: 'No. Semantic completion, source navigation, method signatures, and documentation work without AI. Generative completion is optional.',
  },
  {
    question: 'Can I customize shortcuts and themes?',
    answer: 'Yes. TurboJ supports IntelliJ, Eclipse, VS Code, and classic TurboJ keymaps, individual shortcut customization, and 103 editor themes.',
  },
  {
    question: 'Can I use FormatJ, RefactorJ, and the other tools independently?',
    answer: 'The components are designed as reusable Java libraries. Their source and local distributions exist, but publication to Maven Central and plugin marketplaces is still pending.',
  },
  {
    question: 'Is TurboJ a replacement for IntelliJ IDEA or Eclipse?',
    answer: 'TurboJ takes a different approach: a smaller, keyboard-first interface for Java development. It supports real Maven projects and modern tooling without attempting to reproduce every feature of larger IDEs.',
  },
  {
    question: 'Which platforms are supported?',
    answer: 'The current development preview has been validated on Apple Silicon macOS. Other platforms have not been verified.',
  },
  {
    question: 'Can I download TurboJ?',
    answer: 'The project is in development preview. Source and build instructions are available on GitHub; a signed and notarized public macOS release is not yet available.',
  },
  {
    question: 'Why does it look like an editor from the 1990s?',
    answer: "Because we liked that development experience. The keyboard shortcuts, compact layout, and immediate feedback were good ideas then. They're still good ideas today.",
  },
] as const;

package dev.turboj.refactoring;

import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Operation;
import dev.turboj.refactorj.api.Requests.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import dev.turboj.commandj.swing.EmbeddedOptionPane;

/** Native request forms. The semantic engine remains authoritative for all input validation. */
final class RefactoringForms {
    private RefactoringForms() {}

    static Request request(Window owner, Operation operation, Location location, Symbol symbol,
            List<Symbol> candidates) {
        String selected = symbol == null ? "" : symbol.name();
        Form form = new Form();
        return switch (operation) {
            case RENAME -> {
                JTextField name = form.text("New name", selected);
                JCheckBox hierarchy = form.check("Update package hierarchy", true);
                yield form.show(owner, "Rename") ? new Rename(location, name.getText().strip(),
                        hierarchy.isSelected()) : null;
            }
            case EXTRACT_METHOD -> {
                JTextField name = form.text("Method name", "extracted");
                var visibility = form.visibility();
                JCheckBox duplicates = form.check("Replace duplicate statements", false);
                yield form.show(owner, "Extract Method") ? new ExtractMethod(location, name
                        .getText().strip(), (Visibility) visibility.getSelectedItem(), duplicates
                        .isSelected()) : null;
            }
            case EXTRACT_VARIABLE -> {
                JTextField name = form.text("Variable name", "value");
                var all = form.check("Replace all eligible occurrences", false);
                var fin = form.check("Final variable", false);
                yield form.show(owner, "Extract Variable") ? new ExtractVariable(location, name
                        .getText().strip(), all.isSelected(), fin.isSelected()) : null;
            }
            case EXTRACT_CONSTANT -> {
                JTextField name = form.text("Constant name", "VALUE");
                var visibility = form.visibility();
                var all = form.check("Replace all eligible occurrences", false);
                yield form.show(owner, "Extract Constant") ? new ExtractConstant(location, name
                        .getText().strip(), (Visibility) visibility.getSelectedItem(), all
                        .isSelected()) : null;
            }
            case EXTRACT_FIELD -> {
                JTextField name = form.text("Field name", selected.isEmpty() ? "value" : selected);
                var visibility = form.visibility();
                var initialization = form.combo("Initialize in", FieldInitialization.values());
                yield form.show(owner, "Extract Field") ? new ExtractField(location, name.getText()
                        .strip(), (Visibility) visibility.getSelectedItem(),
                        (FieldInitialization) initialization.getSelectedItem()) : null;
            }
            case EXTRACT_PARAMETER -> {
                JTextField name = form.text("Parameter name", "value");
                yield form.show(owner, "Extract Parameter") ? new ExtractParameter(location, name
                        .getText().strip()) : null;
            }
            case INLINE_VARIABLE -> new InlineVariable(location);
            case INLINE_METHOD -> {
                var all = form.check("Inline all invocations", false);
                var remove = form.check("Remove declaration", false);
                yield form.show(owner, "Inline Method") ? new InlineMethod(location, all
                        .isSelected(), remove.isSelected()) : null;
            }
            case CHANGE_SIGNATURE -> {
                JTextField name = form.text("Method name", selected);
                String signature = symbol == null ? "" : symbol.signature();
                int nameAt = signature.indexOf(selected + "(");
                JTextField type = form.text("Return type", nameAt > 0 ? signature.substring(0,
                        nameAt).strip() : "void");
                var visibility = form.visibility();
                var model = new javax.swing.table.DefaultTableModel(new String[] {"Original index (−1 = new)",
                        "Type", "Name", "Default expression"}, 0);
                seedParameters(model, signature);
                JTable table = new JTable(model);
                table.setPreferredScrollableViewportSize(new Dimension(640, 150));
                form.component("Parameters in new order", new JScrollPane(table));
                JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
                JButton add = new JButton("Add"), remove = new JButton("Remove"),
                        up = new JButton("Move up"), down = new JButton("Move down");
                add.addActionListener(e -> model.addRow(new Object[] {-1, "Object", "value",
                        "null"}));
                remove.addActionListener(e -> {
                    if (table.getSelectedRow() >= 0)
                        model.removeRow(table.getSelectedRow());
                });
                up.addActionListener(e -> move(model, table, -1));
                down.addActionListener(e -> move(model, table, 1));
                for (var button : List.of(add, remove, up, down))
                    actions.add(button);
                form.component("", actions);
                if (!form.show(owner, "Change Signature"))
                    yield null;
                if (table.isEditing())
                    table.getCellEditor().stopCellEditing();
                List<Parameter> parameters = new ArrayList<> ();

                for (int i = 0; i < model.getRowCount(); i++)
                    parameters.add(new Parameter(Integer.parseInt(model.getValueAt(i, 0)
                            .toString()), model.getValueAt(i, 1).toString(), model.getValueAt(i, 2)
                            .toString(), model.getValueAt(i, 3).toString()));
                yield new ChangeSignature(location, name.getText().strip(), type.getText().strip(),
                        (Visibility) visibility.getSelectedItem(), parameters);
            }
            case MOVE -> {
                var kind = form.combo("Move", MoveKind.values());
                var destination = form.text("Destination package / qualified type", "");
                var receiver = form.text("Receiver for instance method", "");
                yield form.show(owner, "Move") ? new Move(location, (MoveKind) kind
                        .getSelectedItem(), destination.getText().strip(), receiver.getText()
                        .strip()) : null;
            }
            case SAFE_DELETE -> new SafeDelete(location);
            case EXTRACT_INTERFACE -> {
                var name = form.text("Interface name", selected.isEmpty() ? "Contract" : selected
                        + "Contract");
                var replace = form.check("Replace eligible usages", false);
                JList<Symbol> members = form.members(candidates, null,
                        "Members (empty = all eligible)");
                yield form.show(owner,
                        "Extract Interface — eligible members") ? new ExtractInterface(location,
                        name.getText().strip(), members.getSelectedValuesList().stream()
                        .map(Symbol::id).toList(), replace.isSelected()) : null;
            }
            case INTRODUCE_INTERFACE -> {
                var name = form.text("Interface name", "Contract");
                yield form.show(owner, "Introduce Interface") ? new IntroduceInterface(location,
                        name.getText().strip(), List.of()) : null;
            }
            case PULL_UP -> {
                var name = form.text("Qualified superclass / interface", "");
                JList<Symbol> members = form.members(candidates, symbol, "Members");
                yield form.show(owner, "Pull Selected Member Up") ? new PullUp(location, name
                        .getText().strip(), members.getSelectedValuesList().stream().map(Symbol::id)
                        .toList()) : null;
            }
            case PUSH_DOWN -> {
                var abstractMember = form.check("Keep abstract declaration", false);
                JList<Symbol> members = form.members(candidates, symbol, "Members");
                yield form.show(owner, "Push Selected Member Down") ? new PushDown(location, members
                        .getSelectedValuesList().stream().map(Symbol::id).toList(), abstractMember
                        .isSelected()) : null;
            }
            case ENCAPSULATE_FIELD -> {
                String suffix = selected.isEmpty() ? "Value" : Character.toUpperCase(selected
                        .charAt(0)) + selected.substring(1);
                var getter = form.text("Getter name", "get" + suffix);
                var setter = form.text("Setter name", "set" + suffix);
                var visibility = form.visibility();
                visibility.setSelectedItem(Visibility.PUBLIC);
                yield form.show(owner, "Encapsulate Field") ? new EncapsulateField(location, getter
                        .getText().strip(), setter.getText().strip(), (Visibility) visibility
                        .getSelectedItem()) : null;
            }
            case CONSTRUCTOR_TO_FACTORY -> {
                var name = form.text("Factory name", "create");
                var protect = form.check("Protect constructor", true);
                yield form.show(owner,
                        "Replace Constructor with Factory") ? new ConstructorToFactory(location,
                        name.getText().strip(), protect.isSelected()) : null;
            }
            case ANONYMOUS_TO_LAMBDA -> new AnonymousToLambda(location);
            case LAMBDA_TO_METHOD_REFERENCE -> new LambdaToMethodReference(location);
            case METHOD_REFERENCE_TO_LAMBDA -> new MethodReferenceToLambda(location);
            case SWITCH_TO_EXPRESSION -> new SwitchToExpression(location);
            case TYPE_TO_VAR -> new TypeToVar(location);
            case VAR_TO_TYPE -> new VarToType(location);
            case ORGANIZE_IMPORTS -> new OrganizeImports(location);
            case ADD_IMPORT -> {
                var name = form.text("Qualified type / member", "");
                var stat = form.check("Static import", false);
                yield form.show(owner, "Add Import") ? new AddImport(location, name.getText()
                        .strip(), stat.isSelected()) : null;
            }
            case REMOVE_IMPORT -> new RemoveImport(location);
            case IMPLEMENT_METHODS -> new ImplementMethods(location, List.of());
            case CREATE_METHOD -> new CreateMethod(location);
            case CREATE_CONSTRUCTOR -> new CreateConstructor(location);
            case CREATE_FIELD -> new CreateField(location);
            case CREATE_LOCAL -> new CreateLocal(location);
            case FIX_TYPE_MISMATCH -> new FixTypeMismatch(location);
            case REMOVE_UNREACHABLE -> new RemoveUnreachable(location);
        };
    }

    private static void move(javax.swing.table.DefaultTableModel model, JTable table,
            int direction) {
        int row = table.getSelectedRow(), target = row + direction;

        if (row < 0 || target < 0 || target >= model.getRowCount())
            return;
        model.moveRow(row, row, target);
        table.setRowSelectionInterval(target, target);
    }

    // Parse only the engine's human-readable signature for a form; the engine revalidates types.
    private static void seedParameters(javax.swing.table.DefaultTableModel model,
            String signature) {
        int start = signature.indexOf('('), end = signature.lastIndexOf(')');

        if (start < 0 || end < start)
            return;
        String parameters = signature.substring(start + 1, end);
        int begin = 0, depth = 0, index = 0;

        for (int i = 0; i <= parameters.length(); i++) {
            char c = i == parameters.length() ? ',' : parameters.charAt(i);

            if (c == '<' || c == '(' || c == '[')
                depth++;
            else if (c == '>' || c == ')' || c == ']')
                depth--;
            if (c == ',' && depth == 0) {
                String part = parameters.substring(begin, i).strip();
                int space = part.lastIndexOf(' ');

                if (!part.isEmpty())
                    model.addRow(new Object[] {index++, space > 0 ? part.substring(0, space) : part,
                            space > 0 ? part.substring(space + 1) : "arg" + index, ""});
                begin = i + 1;
            }
        }
    }

    private static final class Form {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        JTextField text(String label, String value) {
            var field = new JTextField(value, 28);
            component(label, field);
            return field;
        }

        JCheckBox check(String label, boolean selected) {
            var box = new JCheckBox(label, selected);
            component("", box);
            return box;
        }

        <T> JComboBox<T> combo(String label, T[] values) {
            var combo = new JComboBox<> (values);
            component(label, combo);
            return combo;
        }

        JComboBox<Visibility> visibility() {
            return combo("Visibility", Visibility.values());
        }

        JList<Symbol> members(List<Symbol> values, Symbol selected, String label) {
            var list = new JList<> (values.toArray(Symbol[]::new));
            list.setVisibleRowCount(Math.min(8, Math.max(3, values.size())));
            list.setCellRenderer((view, value, index, active, focus) -> {
                var text = new JLabel(value.signature());
                text.setOpaque(true);
                text.setBackground(active ? view.getSelectionBackground() : view.getBackground());
                text.setForeground(active ? view.getSelectionForeground() : view.getForeground());
                text.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
                return text;
            });
            if (selected != null)
                for (int i = 0; i < values.size(); i++)
                    if (values.get(i).id().equals(selected.id()))
                        list.setSelectedIndex(i);
            component(label, new JScrollPane(list));
            return list;
        }

        void component(String label, JComponent value) {
            var c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row++;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(4, 4, 4, 8);
            panel.add(new JLabel(label), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            panel.add(value, c);
            value.getAccessibleContext().setAccessibleName(label);
        }

        boolean show(Window owner, String title) {
            return EmbeddedOptionPane.showConfirmDialog(owner, panel, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    == JOptionPane.OK_OPTION;
        }
    }
}

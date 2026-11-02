import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ComponentProps, ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View, type StyleProp, type TextProps, type TextStyle, type ViewStyle } from 'react-native';
import { useTheme } from './theme';

type IconName = ComponentProps<typeof MaterialCommunityIcons>['name'];

/** Text in the theme's colour, scaled by the user's text-size setting. */
export function AppText({ size = 16, weight, muted, style, ...rest }: TextProps & { size?: number; weight?: TextStyle['fontWeight']; muted?: boolean }) {
  const theme = useTheme();
  return (
    <Text
      {...rest}
      style={[{ fontSize: theme.fs(size), color: muted ? theme.colors.mutedText : theme.colors.text, fontWeight: weight }, style]}
    />
  );
}

interface ButtonProps {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  icon?: IconName;
  variant?: 'primary' | 'outline';
  testID?: string;
  accessibilityLabel?: string;
  children?: ReactNode;
  style?: StyleProp<ViewStyle>;
}

/** A button at least 48 dp tall, with its label always visible next to any icon. */
export function AppButton({ label, onPress, disabled, icon, variant = 'primary', testID, accessibilityLabel, children, style }: ButtonProps) {
  const theme = useTheme();
  const filled = variant === 'primary';
  const fg = filled ? theme.colors.onPrimary : theme.colors.primary;
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? label}
      accessibilityState={{ disabled: !!disabled }}
      style={({ pressed }) => [
        styles.button,
        {
          backgroundColor: filled ? theme.colors.primary : 'transparent',
          borderColor: theme.colors.primary,
          borderWidth: filled ? 0 : theme.borderWidth,
          opacity: disabled ? 0.45 : pressed ? 0.8 : 1,
        },
        style,
      ]}
    >
      {children ?? (
        <>
          {icon ? <MaterialCommunityIcons name={icon} size={theme.fs(22)} color={fg} /> : null}
          <Text style={{ color: fg, fontSize: theme.fs(16), fontWeight: '600' }}>{label}</Text>
        </>
      )}
    </Pressable>
  );
}

/** A bordered surface. */
export function Card({ children, style, testID }: { children: ReactNode; style?: StyleProp<ViewStyle>; testID?: string }) {
  const theme = useTheme();
  return (
    <View
      testID={testID}
      style={[styles.card, { backgroundColor: theme.colors.surface, borderColor: theme.colors.border, borderWidth: theme.borderWidth }, style]}
    >
      {children}
    </View>
  );
}

/** A selectable row with a radio mark; 48 dp tall. */
export function RadioRow({ label, selected, onPress, testID }: { label: string; selected: boolean; onPress: () => void; testID?: string }) {
  const theme = useTheme();
  return (
    <Pressable
      onPress={onPress}
      testID={testID}
      accessibilityRole="radio"
      accessibilityState={{ selected }}
      accessibilityLabel={label}
      style={styles.row}
    >
      <MaterialCommunityIcons
        name={selected ? 'radiobox-marked' : 'radiobox-blank'}
        size={theme.fs(24)}
        color={theme.colors.primary}
      />
      <AppText>{label}</AppText>
    </Pressable>
  );
}

export const spacing = { s: 8, m: 16, l: 24 } as const;

const styles = StyleSheet.create({
  button: { minHeight: 48, borderRadius: 24, paddingHorizontal: 20, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8 },
  card: { borderRadius: 12, padding: 12, gap: 4 },
  row: { minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: 12 },
});
